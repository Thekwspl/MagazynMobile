import test from "node:test";
import assert from "node:assert/strict";
import { CatalogStore } from "../src/catalog.js";
import { searchCatalog } from "../src/catalogTools.js";
import { validToolRequest, validToolResult, parseAgentResponse, type ReadOnlyToolRequest, type ReadOnlyToolResult, type AgentResponse } from "../src/contracts.js";
import { CodexWarehouseAgent, type CodexRunner } from "../src/codexWarehouseAgent.js";

const requests: ReadOnlyToolRequest[] = [
  { id: "stock", tool: "get_current_stock", arguments: { productIds: ["g"] } },
  { id: "person", tool: "get_person_current_items", arguments: { personId: "p" } },
  { id: "yard", tool: "get_shipyard_stock", arguments: { shipyardId: "s" } },
  { id: "orders", tool: "get_active_orders", arguments: { recipientKind: "shipyard", recipientId: "s" } },
  { id: "issues", tool: "get_recent_issues", arguments: { recipientKind: "person", recipientId: "p", limit: 20 } },
];
const results: ReadOnlyToolResult[] = [
  { requestId: "stock", tool: "get_current_stock", data: { stocks: [{ productId: "g", available: -2 }] } },
  { requestId: "person", tool: "get_person_current_items", data: { personId: "p", items: [{ productId: "g", quantity: 1, unit: "szt.", issuedDate: "2026-01-01" }] } },
  { requestId: "yard", tool: "get_shipyard_stock", data: { shipyardId: "s", stocks: [{ productId: "g", quantity: -1, unit: "szt." }] } },
  { requestId: "orders", tool: "get_active_orders", data: { recipientKind: "shipyard", recipientId: "s", orders: [] } },
  { requestId: "issues", tool: "get_recent_issues", data: { recipientKind: "person", recipientId: "p", issues: [] } },
];
const reply = (sessionId: string, needsData: ReadOnlyToolRequest[], status: AgentResponse["status"] = "needs_data"): AgentResponse => ({
  schemaVersion: 1, sessionId, status, intent: "ORDER", recipient: { id: "p", label: "Jan", kind: "person" }, deliveryDate: null,
  items: [{ productId: "g", label: "Rękawice", unit: "szt.", quantity: 1, available: status === "proposal" ? -2 : null }],
  warnings: [], questions: [], candidates: [], needsData, error: null,
});

test("five discriminated requests and minimal results validate; hostile shapes fail", () => {
  requests.forEach((request, i) => {
    assert.equal(validToolRequest(request), true);
    assert.equal(validToolResult(results[i], request), true);
    assert.equal(validToolResult({ ...results[i], requestId: "wrong" }, request), false);
  });
  assert.equal(validToolRequest({ ...requests[4], arguments: { recipientKind: "person", recipientId: "p", limit: 21 } }), false);
  assert.equal(validToolRequest({ ...requests[3], arguments: { recipientKind: "unknown", recipientId: "p" } }), false);
  assert.equal(validToolRequest({ ...requests[0], tool: "issue_items" }), false);
  assert.equal(validToolResult({ ...results[2], data: { shipyardId: "s", stocks: [{ productId: "g", quantity: NaN, unit: "szt." }] } }, requests[2]), false);
  assert.equal(validToolResult({ ...results[1], tool: "get_shipyard_stock" }, requests[1]), false);
  assert.equal(validToolResult({ ...results[0], data: { stocks: [{ productId: "g", available: Infinity }] } }, requests[0]), false);
  assert.throws(() => parseAgentResponse(JSON.stringify(reply("s", [requests[4], requests[4]]))), /CODEX_PROTOCOL_ERROR/);
});

test("catalog shipyard search uses aliases tags leader IDs and returns stable ID", () => {
  const catalog = { revision: 1, people: [], products: [], taskPlaces: [], shipyards: [{ id: "s", name: "Ulstein", aliases: ["Elektro"], tags: ["pokład"], leaders: ["p"] }] };
  for (const query of ["Ulstein", "Elektro", "pokład", "p"])
    assert.equal((searchCatalog(catalog, "search_shipyards", query)[0] as { id: string }).id, "s");
});

test("two simultaneous reads preserve request IDs, session and Codex thread", async () => {
  const catalog = new CatalogStore(); catalog.fullSync({ revision: 1,
    people: [{ id: "p", firstName: "Jan", lastName: "Test" }], products: [{ id: "g", name: "Rękawice", unit: "szt." }],
    shipyards: [{ id: "s", name: "Ulstein" }], taskPlaces: [] });
  const calls: Array<string | undefined> = [];
  const runner: CodexRunner = {
    async start() {}, async accountRead() { return { account: { type: "chatgpt" } }; },
    async runStructuredOrder(prompt, _cwd, threadId) {
      calls.push(threadId);
      const sessionId = prompt.match(/sessionId[: ]+([\da-f-]{36})/)?.[1] ?? "";
      return { threadId: threadId ?? "thread1", response: reply(sessionId, threadId ? [] : requests.slice(0, 2), threadId ? "proposal" : "needs_data"), toolCalls: 1 };
    },
  };
  const agent = new CodexWarehouseAgent(runner, () => ({ script: "mcp", url: "localhost", token: "secret" }), () => catalog.read());
  try {
    const first = await agent.start("Jan rękawice");
    assert.equal((await agent.resumeWithData(first.sessionId, [results[1], results[1]])).error?.code, "TOOL_NOT_ALLOWED");
    assert.equal((await agent.resumeWithData(first.sessionId, [results[0], { ...results[1], requestId: "wrong" }])).error?.code, "TOOL_NOT_ALLOWED");
    const final = await agent.resumeWithData(first.sessionId, [results[1], results[0]]);
    assert.equal(final.status, "proposal");
    assert.deepEqual(calls, [undefined, "thread1"]);
  } finally { agent.close(); }
});
