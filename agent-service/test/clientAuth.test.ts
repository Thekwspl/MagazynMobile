import assert from "node:assert/strict";
import test from "node:test";
import { once } from "node:events";
import { createAgentService } from "../src/server.js";
import type { AgentResponse } from "../src/contracts.js";

// Test fixture only; production must generate an independent random token.
const clientToken = "test-only-" + "7".repeat(64);
const fixture = { revision: 1, people: [{ id: "p1", firstName: "Jan", lastName: "Kowalski" }],
  products: [{ id: "g", name: "Rękawice", unit: "para" }], shipyards: [], taskPlaces: [] };
const auth = { authorization: `Bearer ${clientToken}` };
const fakeCodex = () => {
  let internalToken = "";
  let calls = 0;
  return {
    get internalToken() { return internalToken; }, get calls() { return calls; },
    async start(_command?: string, mcp?: { token: string }) { calls++; internalToken = mcp?.token ?? ""; },
    async accountRead() { calls++; return { account: { type: "chatgpt" } }; },
    async startChatGptDeviceLogin() { calls++; return { verificationUrl: "https://example.invalid/login", userCode: "TEST" }; },
    async startChatGptLogin() { calls++; return { type: "chatgpt" }; },
    async runStructuredOrder(): Promise<never> { throw new Error("unexpected Codex run"); },
    close() {},
  };
};

test("requires a client secret at startup", () => {
  assert.throws(() => createAgentService({ clientToken: "" }), /AGENT_CLIENT_TOKEN/);
});

test("public v1 endpoints require Bearer and internal MCP keeps a different secret", async () => {
  const codex = fakeCodex();
  const service = createAgentService({ mode: "local", clientToken, codex });
  service.server.listen(0, "127.0.0.1"); await once(service.server, "listening");
  const address = service.server.address(); assert.ok(address && typeof address !== "string");
  const base = `http://127.0.0.1:${address.port}`;
  const request = (path: string, method = "GET", headers: Record<string, string> = {}, value?: unknown) =>
    fetch(base + path, { method, headers: { ...headers, ...(value ? { "content-type": "application/json" } : {}) },
      body: value ? JSON.stringify(value) : undefined });
  try {
    const endpoints = [
      ["/v1/catalog/full-sync", "PUT", fixture], ["/v1/catalog/delta-sync", "PATCH", {}],
      ["/v1/sessions/message", "POST", { message: "Kowalski rękawice" }],
      ["/v1/sessions/s1/tool-results", "POST", { results: [] }],
      ["/v1/sessions/s1/choice", "POST", { candidateId: "p1" }],
      ["/v1/auth/status", "GET", undefined],
      ["/v1/auth/chatgpt/start", "POST", {}], ["/v1/auth/chatgpt/device-code", "POST", {}],
    ] as const;
    for (const [path, method, value] of endpoints) {
      const missing = await request(path, method, {}, value);
      const wrong = await request(path, method, { authorization: "Bearer invalid" }, value);
      assert.equal(missing.status, 401, path); assert.equal(wrong.status, 401, path);
      assert.ok(!(await missing.text()).includes(clientToken));
      assert.ok(!(await wrong.text()).includes(clientToken));
    }
    assert.equal(codex.calls, 0);
    const health = await request("/health");
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: "ok" });
    assert.equal((await request("/v1/unsupported", "POST", auth)).status, 403);
    assert.equal((await request("/v1/catalog/full-sync", "PUT", auth, fixture)).status, 204);
    const initial = await (await request("/v1/sessions/message", "POST", auth,
      { message: "Kowalski 2 rękawice" })).json() as AgentResponse;
    assert.equal(initial.status, "needs_data");
    const resumed = await (await request(`/v1/sessions/${initial.sessionId}/tool-results`, "POST", auth,
      { results: [{ requestId: initial.needsData[0].id, tool: "get_current_stock",
        data: { stocks: [{ productId: "g", available: -2 }] } }] })).json() as AgentResponse;
    assert.equal(resumed.status, "proposal");
    assert.equal((await request(`/v1/sessions/${initial.sessionId}/choice`, "POST", auth, { candidateId: "p1" })).status, 200);
    const authorizedStatus = await request("/v1/auth/status", "GET", auth);
    assert.equal(authorizedStatus.status, 200);
    assert.ok(!(await authorizedStatus.text()).includes(clientToken));
    assert.equal((await request("/v1/auth/chatgpt/start", "POST", auth, {})).status, 200);
    assert.equal((await request("/v1/auth/chatgpt/device-code", "POST", auth, {})).status, 200);
    const internal = { tool: "search_products", query: "Rękawice" };
    assert.equal((await request("/_internal/catalog/search", "POST", auth, internal)).status, 403);
    assert.ok(codex.internalToken && codex.internalToken !== clientToken);
    assert.equal((await request("/_internal/catalog/search", "POST",
      { authorization: `Bearer ${codex.internalToken}` }, internal)).status, 200);
  } finally { service.close(); }
});

test("rotation accepts the new credential and rejects the previous one", async () => {
  const replacement = "rotated-test-only-" + "8".repeat(64);
  const service = createAgentService({ mode: "local", clientToken: replacement });
  service.server.listen(0, "127.0.0.1"); await once(service.server, "listening");
  const address = service.server.address(); assert.ok(address && typeof address !== "string");
  const url = `http://127.0.0.1:${address.port}/v1/catalog/full-sync`;
  try {
    const sync = (token: string) => fetch(url, { method: "PUT", headers: { authorization: `Bearer ${token}`,
      "content-type": "application/json" }, body: JSON.stringify(fixture) });
    assert.equal((await sync(clientToken)).status, 401);
    assert.equal((await sync(replacement)).status, 204);
  } finally { service.close(); }
});

test("bounded session requests recover after a deterministic window", async () => {
  let now = 1000;
  const service = createAgentService({ mode: "local", clientToken, rateLimit: { max: 2, windowMs: 1000, now: () => now } });
  service.server.listen(0, "127.0.0.1"); await once(service.server, "listening");
  const address = service.server.address(); assert.ok(address && typeof address !== "string");
  const send = () => fetch(`http://127.0.0.1:${address.port}/v1/sessions/message`, {
    method: "POST", headers: { ...auth, "content-type": "application/json" }, body: JSON.stringify({ message: "test" }),
  });
  try {
    assert.equal((await send()).status, 200);
    assert.equal((await send()).status, 200);
    assert.equal((await send()).status, 429);
    now += 1000;
    assert.equal((await send()).status, 200);
  } finally { service.close(); }
});
