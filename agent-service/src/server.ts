import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { CatalogStore, type CatalogDelta, type CatalogSnapshot } from "./catalog.js";
import { CodexAppServerClient } from "./codexAppServerClient.js";
import type { ReadOnlyToolResult } from "./contracts.js";
import { WarehouseAgent } from "./warehouseAgent.js";

const catalog = new CatalogStore();
const agent = new WarehouseAgent(() => catalog.read());
const codex = new CodexAppServerClient();

const json = (response: ServerResponse, status: number, value: unknown): void => {
  response.writeHead(status, { "content-type": "application/json; charset=utf-8" });
  response.end(JSON.stringify(value));
};

const body = async <T>(request: IncomingMessage): Promise<T> => {
  const chunks: Buffer[] = [];
  for await (const chunk of request) chunks.push(Buffer.from(chunk));
  return JSON.parse(Buffer.concat(chunks).toString("utf8")) as T;
};

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url ?? "/", "http://localhost");
    if (request.method === "GET" && url.pathname === "/health") {
      return json(response, 200, { status: "ok", catalogRevision: catalog.read().revision });
    }
    if (request.method === "PUT" && url.pathname === "/v1/catalog/full-sync") {
      catalog.fullSync(await body<CatalogSnapshot>(request));
      return json(response, 204, null);
    }
    if (request.method === "PATCH" && url.pathname === "/v1/catalog/delta-sync") {
      catalog.deltaSync(await body<CatalogDelta>(request));
      return json(response, 204, null);
    }
    if (request.method === "POST" && url.pathname === "/v1/sessions/message") {
      const payload = await body<{ message: string }>(request);
      return json(response, 200, agent.start(payload.message));
    }
    const resume = url.pathname.match(/^\/v1\/sessions\/([^/]+)\/tool-results$/);
    if (request.method === "POST" && resume) {
      const payload = await body<{ results: ReadOnlyToolResult[] }>(request);
      return json(response, 200, agent.resumeWithData(decodeURIComponent(resume[1]), payload.results));
    }
    if (request.method === "GET" && url.pathname === "/v1/auth/status") {
      await codex.start();
      return json(response, 200, await codex.accountRead());
    }
    if (request.method === "POST" && url.pathname === "/v1/auth/chatgpt/device-code") {
      await codex.start();
      return json(response, 200, await codex.startChatGptDeviceLogin());
    }
    return json(response, 404, { error: "not_found" });
  } catch (error) {
    return json(response, 400, {
      error: error instanceof Error ? error.message : "unknown_error",
    });
  }
});

const port = Number(process.env.PORT ?? 8787);
server.listen(port, "127.0.0.1", () => {
  console.log(`MagazynMobile agent POC: http://127.0.0.1:${port}`);
});

const shutdown = (): void => {
  codex.close();
  server.close();
};
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
