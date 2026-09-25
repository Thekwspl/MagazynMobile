import { randomBytes, timingSafeEqual } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { fileURLToPath } from "node:url";
import { CatalogStore, type CatalogDelta, type CatalogSnapshot } from "./catalog.js";
import { searchCatalog, SEARCH_TOOLS, type SearchTool } from "./catalogTools.js";
import { CodexAppServerClient } from "./codexAppServerClient.js";
import { CodexWarehouseAgent, type CodexRunner } from "./codexWarehouseAgent.js";
import type { ReadOnlyToolResult } from "./contracts.js";
import { WarehouseAgent } from "./warehouseAgent.js";

const json = (response: ServerResponse, status: number, value: unknown): void => {
  if (status === 204) { response.writeHead(204); response.end(); return; }
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(value));
};
const body = async <T>(request: IncomingMessage): Promise<T> => {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 2_000_000) throw new Error("Żądanie jest za duże.");
    chunks.push(Buffer.from(chunk));
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as T;
};

type ServiceCodex = CodexRunner & Pick<CodexAppServerClient, "close" | "startChatGptDeviceLogin" | "startChatGptLogin">;
export function createAgentService(options: { mode?: "local" | "codex"; codex?: ServiceCodex } = {}) {
  const mode = options.mode ?? (process.env.AGENT_MODE ?? "local");
  if (mode !== "local" && mode !== "codex") throw new Error("AGENT_MODE musi mieć wartość local albo codex.");
  const catalog = new CatalogStore();
  const local = new WarehouseAgent(() => catalog.read());
  const codex = options.codex ?? new CodexAppServerClient();
  const secret = randomBytes(32).toString("hex");
  const mcpScript = fileURLToPath(new URL("./catalogMcp.js", import.meta.url));
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url ?? "/", "http://localhost");
      if (request.method === "POST" && url.pathname === "/_internal/catalog/search") {
        const supplied = request.headers.authorization?.replace(/^Bearer /, "") ?? "";
        const valid = supplied.length === secret.length && timingSafeEqual(Buffer.from(supplied), Buffer.from(secret));
        if (!valid || request.socket.remoteAddress !== "127.0.0.1") return json(response, 403, { error: "forbidden" });
        const payload = await body<{ tool: SearchTool; query: string }>(request);
        if (!SEARCH_TOOLS.includes(payload.tool)) return json(response, 400, { error: "unknown_tool" });
        return json(response, 200, searchCatalog(catalog.read(), payload.tool, payload.query));
      }
      if (request.method === "GET" && url.pathname === "/health")
        return json(response, 200, { status: "ok", mode, catalogRevision: catalog.read().revision });
      if (request.method === "PUT" && url.pathname === "/v1/catalog/full-sync") {
        catalog.fullSync(await body<CatalogSnapshot>(request)); return json(response, 204, null);
      }
      if (request.method === "PATCH" && url.pathname === "/v1/catalog/delta-sync") {
        catalog.deltaSync(await body<CatalogDelta>(request)); return json(response, 204, null);
      }
      if (request.method === "POST" && url.pathname === "/v1/sessions/message") {
        const payload = await body<{ message: string }>(request);
        return json(response, 200, mode === "codex" ? await agent.start(payload.message) : local.start(payload.message));
      }
      const resume = url.pathname.match(/^\/v1\/sessions\/([^/]+)\/tool-results$/);
      if (request.method === "POST" && resume) {
        const payload = await body<{ results: ReadOnlyToolResult[] }>(request);
        const sessionId = decodeURIComponent(resume[1]);
        return json(response, 200, mode === "codex"
          ? await agent.resumeWithData(sessionId, payload.results)
          : local.resumeWithData(sessionId, payload.results));
      }
      const choice = url.pathname.match(/^\/v1\/sessions\/([^/]+)\/choice$/);
      if (request.method === "POST" && choice) {
        const payload = await body<{ candidateId: string }>(request);
        const sessionId = decodeURIComponent(choice[1]);
        return json(response, 200, mode === "codex"
          ? await agent.resumeWithChoice(sessionId, payload.candidateId)
          : local.resumeWithChoice(sessionId, payload.candidateId));
      }
      if (request.method === "GET" && url.pathname === "/v1/auth/status") {
        await codex.start("codex", mcp()); return json(response, 200, await codex.accountRead());
      }
      if (request.method === "POST" && url.pathname === "/v1/auth/chatgpt/device-code") {
        await codex.start("codex", mcp()); return json(response, 200, await codex.startChatGptDeviceLogin());
      }
      if (request.method === "POST" && url.pathname === "/v1/auth/chatgpt/start") {
        await codex.start("codex", mcp()); return json(response, 200, await codex.startChatGptLogin());
      }
      return json(response, 404, { error: "not_found" });
    } catch (error) {
      const message = error instanceof Error ? error.message : "unknown_error";
      const status = message.includes("TIMEOUT") ? 504 : message.includes("CODEX_PROTOCOL_ERROR") ? 502 : 400;
      return json(response, status, { error: message });
    }
  });
  const mcp = () => {
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("Serwis nie nasłuchuje.");
    return { script: mcpScript, url: `http://127.0.0.1:${address.port}/_internal/catalog/search`, token: secret };
  };
  const agent = new CodexWarehouseAgent(codex, mcp, () => catalog.read());
  const close = (): void => { codex.close(); agent.close(); server.close(); };
  return { server, catalog, close };
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  const service = createAgentService();
  const port = Number(process.env.PORT ?? 8787);
  service.server.listen(port, "127.0.0.1", () => {
    console.log(`MagazynMobile agent POC: http://127.0.0.1:${port}`);
  });
  process.on("SIGINT", service.close);
  process.on("SIGTERM", service.close);
}
