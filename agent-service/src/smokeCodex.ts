import { once } from "node:events";
import { createAgentService } from "./server.js";
import type { AgentResponse } from "./contracts.js";

const fixture = {
  revision: 1,
  people: [{ id: "person-jan", firstName: "Jan", lastName: "Kowalski" }],
  products: [
    { id: "product-gloves-xl", name: "Rękawice robocze", variant: "XL", unit: "para", aliases: ["rękawice XL"] },
    { id: "product-glasses", name: "Okulary ochronne", unit: "szt.", aliases: ["okulary"] },
  ],
  shipyards: [], taskPlaces: [],
};
const service = createAgentService({ mode: "codex" });
service.server.listen(0, "127.0.0.1");
await once(service.server, "listening");
const address = service.server.address();
if (!address || typeof address === "string") throw new Error("Brak adresu serwera.");
const root = `http://127.0.0.1:${address.port}`;
const authorized = { authorization: `Bearer ${process.env.AGENT_CLIENT_TOKEN}` };
const post = async (path: string, payload: unknown): Promise<AgentResponse> => {
  const reply = await fetch(root + path, { method: "POST", headers: { "content-type": "application/json", ...authorized }, body: JSON.stringify(payload) });
  const result = await reply.json() as AgentResponse & { error?: { message: string } | string };
  if (!reply.ok) throw new Error(`HTTP ${reply.status}: ${JSON.stringify(result)}`);
  return result;
};
try {
  const authReply = await fetch(root + "/v1/auth/status", { headers: authorized });
  if (!authReply.ok) throw new Error(`Nie można uruchomić Codexa: HTTP ${authReply.status}`);
  const auth = await authReply.json() as { account?: { type?: string } };
  if (auth.account?.type !== "chatgpt") {
    console.error("CHATGPT_AUTH_REQUIRED. Zaloguj się kontem ChatGPT przez POST /v1/auth/chatgpt/device-code, otwórz verificationUrl, wpisz userCode i ponów smoke test. Możesz też uruchomić: codex login --device-auth.");
    process.exitCode = 1;
  } else {
    const sync = await fetch(root + "/v1/catalog/full-sync", { method: "PUT", headers: { "content-type": "application/json", ...authorized }, body: JSON.stringify(fixture) });
    if (!sync.ok) throw new Error(`Synchronizacja fixture: HTTP ${sync.status}`);
    const first = await post("/v1/sessions/message", { message: "Kowalski jutro 2 rękawice XL i okulary" });
    if (first.status !== "needs_data" || first.items.length !== 2 || first.needsData.length !== 1)
      throw new Error(`Codex nie zwrócił oczekiwanego needs_data: ${JSON.stringify(first)}`);
    // The adapter rejects the turn unless a completed warehouse_catalog MCP call occurred.
    const request = first.needsData[0];
    const final = await post(`/v1/sessions/${first.sessionId}/tool-results`, { results: [{
      requestId: request.id, tool: "get_current_stock", data: { stocks: request.arguments.productIds.map(productId => ({ productId, available: 10 })) },
    }] });
    if (final.status !== "proposal" || final.sessionId !== first.sessionId || final.items.some(i => i.available !== 10))
      throw new Error(`Wznowiony turn nie zwrócił proposal: ${JSON.stringify(final)}`);
    console.log(`PASS: rzeczywisty Codex turn i MCP search → needs_data → ten sam thread → proposal (sessionId ${first.sessionId}).`);
  }
} catch (error) {
  process.exitCode = 1;
  console.error(error);
} finally { service.close(); }
