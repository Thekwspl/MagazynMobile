import assert from "node:assert/strict";
import test from "node:test";
import { once } from "node:events";
import { CatalogStore, type CatalogSnapshot } from "../src/catalog.js";
import { searchCatalog } from "../src/catalogTools.js";
import { CodexWarehouseAgent, type CodexRunner } from "../src/codexWarehouseAgent.js";
import { CodexAppServerClient, type CodexTransport } from "../src/codexAppServerClient.js";
import { parseAgentResponse, type AgentResponse } from "../src/contracts.js";
import { createAgentService } from "../src/server.js";

const fixture: CatalogSnapshot = { revision: 1,
  people: [{ id: "p1", firstName: "Jan", lastName: "Kowalski" }],
  products: [{ id: "g", name: "Rękawice", variant: "XL", unit: "para" }, { id: "o", name: "Okulary", unit: "szt." }],
  shipyards: [], taskPlaces: [] };
const clientToken = "fixture-token-" + "x".repeat(64);
const authorized = { authorization: `Bearer ${clientToken}` };
const response = (sessionId: string, status: AgentResponse["status"]): AgentResponse => ({
  schemaVersion: 1, sessionId, status, intent: "ORDER", recipient: { id: "p1", label: "Jan Kowalski", kind: "person" },
  deliveryDate: null, error: null,
  items: [{ productId: "g", label: "Rękawice XL", quantity: 2, unit: "para", available: status === "proposal" ? 3 : null }],
  warnings: [], questions: status === "needs_user_choice" ? ["Którego Kowalskiego?"] : [],
  candidates: status === "needs_user_choice" ? [{ id: "p1", label: "Jan Kowalski", kind: "person" }] : [],
  needsData: status === "needs_data" ?
    [{ id: "req1", tool: "get_current_stock", arguments: { productIds: ["g"] } }] : [],
});
class FakeRunner implements CodexRunner {
  auth = true;
  calls: Array<{ prompt: string; threadId?: string }> = [];
  resultStatus: AgentResponse["status"] = "needs_data";
  malformed = false;
  forceProposalOnChoice = false;
  async start(): Promise<void> {}
  async accountRead(): Promise<unknown> { return { account: this.auth ? { type: "chatgpt" } : { type: "apiKey" } }; }
  async runStructuredOrder(prompt: string, _cwd: string, threadId?: string) {
    this.calls.push({ prompt, threadId });
    const sessionId = prompt.match(/sessionId[: ]+([\da-f-]{36})/)?.[1] ?? "";
    const value = response(sessionId, threadId
      ? (prompt.includes("ręcznie wybrał") && !this.forceProposalOnChoice ? "needs_data" : "proposal")
      : this.resultStatus);
    if (this.malformed) (value as unknown as Record<string, unknown>).candidates = [{}];
    return { threadId: threadId ?? `thread-${sessionId}`, response: parseAgentResponse(JSON.stringify(value)), toolCalls: 2 };
  }
}
const setup = () => {
  const store = new CatalogStore(); store.fullSync(fixture);
  const fake = new FakeRunner();
  const agent = new CodexWarehouseAgent(fake, () => ({ script: "mcp", url: "local", token: "secret" }), () => store.read());
  return { agent, fake };
};
test("message invokes Codex, needs_data resumes the same thread, then proposal", async () => {
  const { agent, fake } = setup();
  try {
    const first = await agent.start("Kowalski jutro 2 rękawice XL i okulary");
    assert.equal(first.status, "needs_data");
    const final = await agent.resumeWithData(first.sessionId, [{ requestId: "req1", tool: "get_current_stock", data: { stocks: [{ productId: "g", available: 3 }] } }]);
    assert.equal(final.status, "proposal");
    assert.equal(fake.calls[1].threadId, `thread-${first.sessionId}`);
    assert.match(fake.calls[0].prompt, /Kowalski jutro 2 rękawice XL i okulary/);
  } finally { agent.close(); }
});
test("choice, auth gate and invalid response", async () => {
  const { agent, fake } = setup();
  try {
    fake.auth = false;
    assert.equal((await agent.start("Kowalski")).error?.code, "CHATGPT_AUTH_REQUIRED");
    assert.equal(fake.calls.length, 0);
    fake.auth = true; fake.resultStatus = "needs_user_choice";
    const choice = await agent.start("Kowalski");
    assert.equal(choice.status, "needs_user_choice");
    assert.equal(choice.candidates[0].id, "p1");
    fake.malformed = true;
    await assert.rejects(agent.start("Kowalski"), /CODEX_PROTOCOL_ERROR/);
  } finally { agent.close(); }
});
test("manual choice resumes the same Codex thread", async () => {
  const { agent, fake } = setup();
  try {
    fake.resultStatus = "needs_user_choice";
    const initial = await agent.start("Kowalski rękawice");
    const afterChoice = await agent.resumeWithChoice(initial.sessionId, "p1");
    assert.equal(afterChoice.status, "needs_data");
    const proposal = await agent.resumeWithData(initial.sessionId, [{ requestId: "req1", tool: "get_current_stock", data: { stocks: [{ productId: "g", available: 3 }] } }]);
    assert.equal(proposal.status, "proposal");
    assert.equal(fake.calls[1].threadId, `thread-${initial.sessionId}`);
    assert.equal((await agent.resumeWithChoice(initial.sessionId, "unknown")).error?.code, "INVALID_STATE");
  } finally { agent.close(); }
});
test("Codex cannot propose stock before the requested Room read", async () => {
  const { agent, fake } = setup();
  try {
    fake.resultStatus = "proposal";
    await assert.rejects(agent.start("Kowalski rękawice"), /CODEX_PROTOCOL_ERROR/);
    fake.resultStatus = "needs_user_choice";
    fake.forceProposalOnChoice = true;
    const choice = await agent.start("Kowalski rękawice");
    await assert.rejects(agent.resumeWithChoice(choice.sessionId, "p1"), /CODEX_PROTOCOL_ERROR/);
  } finally { agent.close(); }
});
test("no write tool, and catalog reads are bounded", async () => {
  const { agent } = setup();
  try {
    const first = await agent.start("Kowalski rękawice");
    assert.equal((await agent.resumeWithData(first.sessionId, [{ requestId: "req1", tool: "issue_items", data: { stocks: [] } }])).error?.code, "TOOL_NOT_ALLOWED");
    assert.deepEqual(searchCatalog(fixture, "search_people", "Kowal").map((p: any) => p.id), ["p1"]);
    assert.ok(!["search_people", "search_products", "search_shipyards", "search_task_places"].includes("write_stock"));
  } finally { agent.close(); }
});
test("HTTP message endpoint uses Codex adapter", async () => {
  const fake = new FakeRunner();
  const service = createAgentService({ mode: "codex", clientToken, codex: {
    ...fake, start: fake.start.bind(fake), accountRead: fake.accountRead.bind(fake),
    runStructuredOrder: fake.runStructuredOrder.bind(fake), close() {},
    async startChatGptDeviceLogin() { return {}; }, async startChatGptLogin() { return {}; },
  } });
  service.server.listen(0, "127.0.0.1"); await once(service.server, "listening");
  const address = service.server.address(); assert.ok(address && typeof address !== "string");
  const base = `http://127.0.0.1:${address.port}`;
  try {
    await fetch(`${base}/v1/catalog/full-sync`, { method: "PUT", headers: { "content-type": "application/json", ...authorized }, body: JSON.stringify(fixture) });
    const result = await fetch(`${base}/v1/sessions/message`, { method: "POST", headers: { "content-type": "application/json", ...authorized }, body: JSON.stringify({ message: "Kowalski rękawice" }) });
    assert.equal((await result.json() as AgentResponse).status, "needs_data");
    assert.equal(fake.calls.length, 1);
    const denied = await fetch(`${base}/_internal/catalog/search`, { method: "POST", body: "{}" });
    assert.equal(denied.status, 403);
  } finally { service.close(); }
});
test("HTTP choice endpoint preserves session and rejects unknown candidate", async () => {
  const fake = new FakeRunner(); fake.resultStatus = "needs_user_choice";
  const service = createAgentService({ mode: "codex", clientToken, codex: {
    start: fake.start.bind(fake), accountRead: fake.accountRead.bind(fake),
    runStructuredOrder: fake.runStructuredOrder.bind(fake), close() {},
    async startChatGptDeviceLogin() { return {}; }, async startChatGptLogin() { return {}; },
  } });
  service.server.listen(0, "127.0.0.1"); await once(service.server, "listening");
  const address = service.server.address(); assert.ok(address && typeof address !== "string");
  const base = `http://127.0.0.1:${address.port}`;
  try {
    await fetch(`${base}/v1/catalog/full-sync`, { method: "PUT", headers: { "content-type": "application/json", ...authorized }, body: JSON.stringify(fixture) });
    const send = async (path: string, payload: unknown) => fetch(`${base}${path}`, {
      method: "POST", headers: { "content-type": "application/json", ...authorized }, body: JSON.stringify(payload),
    }).then(reply => reply.json() as Promise<AgentResponse>);
    const first = await send("/v1/sessions/message", { message: "Kowalski rękawice" });
    const invalid = await send(`/v1/sessions/${first.sessionId}/choice`, { candidateId: "unknown" });
    assert.equal(invalid.error?.code, "INVALID_CHOICE");
    const next = await send(`/v1/sessions/${first.sessionId}/choice`, { candidateId: "p1" });
    assert.equal(next.sessionId, first.sessionId);
    assert.equal(next.status, "needs_data");
    const final = await send(`/v1/sessions/${first.sessionId}/tool-results`, { results: [{ requestId: "req1", tool: "get_current_stock", data: { stocks: [{ productId: "g", available: 3 }] } }] });
    assert.equal(final.status, "proposal");
    assert.equal(fake.calls[1].threadId, `thread-${first.sessionId}`);
  } finally { service.close(); }
});

class FakeTransport implements CodexTransport {
  private onMessage: (line: string) => void = () => {};
  private onDeath: (error: Error) => void = () => {};
  requests: any[] = [];
  auto = true;
  write(line: string): void {
    const req = JSON.parse(line);
    this.requests.push(req);
    if (!this.auto || !req.id) return;
    queueMicrotask(() => {
      const result = req.method === "thread/start" ? { thread: { id: `t${req.id}` } } :
        req.method === "thread/resume" ? { thread: { id: req.params.threadId } } :
        req.method === "turn/start" ? { turn: { id: `u${req.id}` } } : {};
      this.emit({ id: req.id, result });
    });
  }
  emit(event: unknown): void { this.onMessage(JSON.stringify(event)); }
  onLine(callback: (line: string) => void): void { this.onMessage = callback; }
  onExit(callback: (error: Error) => void): void { this.onDeath = callback; }
  close(): void { this.onDeath(new Error("closed")); }
}
test("two simultaneous turns route events by thread and turn", async () => {
  const transport = new FakeTransport(); const client = new CodexAppServerClient(() => transport);
  await client.start();
  const a = client.runStructuredOrder("A", "/tmp"); const b = client.runStructuredOrder("B", "/tmp");
  await new Promise(resolve => setTimeout(resolve, 10));
  const turns = transport.requests.filter(r => r.method === "turn/start");
  assert.equal(turns.length, 2);
  const [first, second] = turns;
  const finish = (req: any, text: string) => {
    const threadId = req.params.threadId, turnId = `u${req.id}`;
    transport.emit({ method: "item/completed", params: { threadId, turnId, item: { type: "agentMessage", text } } });
    transport.emit({ method: "turn/completed", params: { threadId, turn: { id: turnId, status: "completed" } } });
  };
  finish(second, JSON.stringify(response("B", "proposal")));
  finish(first, JSON.stringify(response("A", "needs_data")));
  assert.equal((await a).response.sessionId, "A"); assert.equal((await b).response.sessionId, "B");
  client.close();
});
test("request and turn timeouts are explicit", async () => {
  const transport = new FakeTransport(); transport.auto = false;
  const client = new CodexAppServerClient(() => transport, 20, 20);
  await assert.rejects(client.start(), /CODEX_REQUEST_TIMEOUT: initialize/);
  client.close();
  const transport2 = new FakeTransport(); const client2 = new CodexAppServerClient(() => transport2, 100, 20);
  await client2.start();
  await assert.rejects(client2.runStructuredOrder("x", "/tmp"), /CODEX_TURN_TIMEOUT/);
  client2.close();
});

test("MCP subprocess lists only four read tools and fetches from live CatalogStore", async () => {
  const { Client } = await import("@modelcontextprotocol/sdk/client/index.js");
  const { StdioClientTransport } = await import("@modelcontextprotocol/sdk/client/stdio.js");
  const store = new CatalogStore(); store.fullSync(fixture);
  const http = (await import("node:http")).createServer(async (req, res) => {
    if (req.headers.authorization !== "Bearer test-token") { res.writeHead(403); res.end(); return; }
    const chunks: Buffer[] = []; for await (const chunk of req) chunks.push(Buffer.from(chunk));
    const { tool, query } = JSON.parse(Buffer.concat(chunks).toString("utf8"));
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify(searchCatalog(store.read(), tool, query)));
  });
  http.listen(0, "127.0.0.1"); await once(http, "listening");
  const address = http.address(); assert.ok(address && typeof address !== "string");
  const client = new Client({ name: "warehouse-test", version: "1.0.0" });
  const transport = new StdioClientTransport({ command: process.execPath,
    args: [new URL("../src/catalogMcp.js", import.meta.url).pathname],
    env: { ...process.env, WAREHOUSE_CATALOG_URL: `http://127.0.0.1:${address.port}/`, WAREHOUSE_CATALOG_TOKEN: "test-token" } });
  try {
    await client.connect(transport);
    assert.deepEqual((await client.listTools()).tools.map(t => t.name).sort(),
      ["search_people", "search_products", "search_shipyards", "search_task_places"].sort());
    const result = await client.callTool({ name: "search_people", arguments: { query: "Kowal" } });
    assert.match(JSON.stringify(result), /p1/);
    assert.equal((await client.callTool({ name: "write_stock", arguments: {} })).isError, true);
  } finally { await client.close(); http.close(); }
});
