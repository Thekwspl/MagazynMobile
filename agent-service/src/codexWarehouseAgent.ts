import { randomUUID } from "node:crypto";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { WAREHOUSE_AGENT_INSTRUCTIONS } from "./agentInstructions.js";
import type { CatalogSnapshot } from "./catalog.js";
import { PROTOCOL_VERSION, type AgentResponse, type ReadOnlyToolResult } from "./contracts.js";

export interface CodexRunner {
  start(command?: string, mcp?: { script: string; url: string; token: string }): Promise<void>;
  accountRead(): Promise<unknown>;
  runStructuredOrder(prompt: string, cwd: string, threadId?: string): Promise<{ threadId: string; response: AgentResponse; toolCalls: number }>;
}
interface Session { threadId: string; response: AgentResponse; busy: boolean }
export class CodexWarehouseAgent {
  private readonly sessions = new Map<string, Session>();
  private readonly cwd = mkdtempSync(join(tmpdir(), "magazyn-agent-"));
  constructor(private readonly codex: CodexRunner, private readonly mcp: () => { script: string; url: string; token: string },
    private readonly catalog: () => CatalogSnapshot) {}
  close(): void { rmSync(this.cwd, { recursive: true, force: true }); }

  private error(sessionId: string, code: string, message: string): AgentResponse {
    return { schemaVersion: PROTOCOL_VERSION, sessionId, status: "error", intent: "ORDER", items: [],
      warnings: [], questions: [], candidates: [], needsData: [], error: { code, message } };
  }
  private async auth(): Promise<boolean> {
    await this.codex.start("codex", this.mcp());
    const state = await this.codex.accountRead() as { account?: { type?: string }; authMode?: string };
    return state?.account?.type === "chatgpt" || state?.authMode === "chatgpt";
  }
  async start(message: string): Promise<AgentResponse> {
    const sessionId = randomUUID();
    if (typeof message !== "string" || !message.trim()) return this.error(sessionId, "INVALID_MESSAGE", "Wiadomość jest pusta.");
    if (!await this.auth()) return this.error(sessionId, "CHATGPT_AUTH_REQUIRED", "Zaloguj Codex kontem ChatGPT przez /v1/auth/chatgpt/device-code.");
    const prompt = `${WAREHOUSE_AGENT_INSTRUCTIONS}\nDzisiejsza data UTC: ${new Date().toISOString().slice(0, 10)}. sessionId: ${sessionId}\nWiadomość użytkownika: ${JSON.stringify(message)}`;
    const result = await this.codex.runStructuredOrder(prompt, this.cwd);
    if (result.response.sessionId !== sessionId) throw new Error("CODEX_PROTOCOL_ERROR: niezgodny sessionId.");
    if (result.toolCalls < 1) throw new Error("CODEX_PROTOCOL_ERROR: Codex nie użył narzędzi katalogu.");
    const snapshot = this.catalog();
    const response = result.response;
    if (response.recipient && !(response.recipient.kind === "person"
      ? snapshot.people.some(p => p.id === response.recipient!.id)
      : snapshot.shipyards.some(s => s.id === response.recipient!.id)))
      throw new Error("CODEX_PROTOCOL_ERROR: nieznany odbiorca.");
    if (response.items.some(item => !snapshot.products.some(p => p.id === item.productId && !p.hidden)) ||
      response.needsData.some(req => req.arguments.productIds.some(id => !response.items.some(item => item.productId === id))))
      throw new Error("CODEX_PROTOCOL_ERROR: nieznany produkt w odpowiedzi.");
    this.sessions.set(sessionId, { threadId: result.threadId, response: result.response, busy: false });
    return result.response;
  }
  async resumeWithData(sessionId: string, results: ReadOnlyToolResult[]): Promise<AgentResponse> {
    const state = this.sessions.get(sessionId);
    if (!state) return this.error(sessionId, "SESSION_NOT_FOUND", "Nie znaleziono sesji.");
    if (state.busy) return this.error(sessionId, "SESSION_BUSY", "Sesja przetwarza już wynik.");
    if (state.response.status !== "needs_data") return this.error(sessionId, "INVALID_STATE", "Sesja nie oczekuje na dane.");
    const requests = state.response.needsData;
    if (!Array.isArray(results) || results.length !== requests.length || new Set(results.map(r => r.requestId)).size !== results.length ||
      results.some(r => r.tool !== "get_current_stock" || !requests.some(q => q.id === r.requestId && q.tool === r.tool)))
      return this.error(sessionId, "TOOL_NOT_ALLOWED", "Dozwolone są wyłącznie żądane odczyty magazynowe.");
    for (const result of results) {
      const request = requests.find(q => q.id === result.requestId)!;
      const stocks = result.data?.stocks;
      if (!Array.isArray(stocks) || stocks.length !== request.arguments.productIds.length ||
        new Set(stocks.map(s => s.productId)).size !== stocks.length ||
        stocks.some(s => !request.arguments.productIds.includes(s.productId) || !Number.isFinite(s.available)))
        return this.error(sessionId, "INCOMPLETE_DATA", "Brakuje poprawnego wyniku odczytu dla części produktów.");
    }
    state.busy = true;
    try {
      if (!await this.auth()) return this.error(sessionId, "CHATGPT_AUTH_REQUIRED", "Zaloguj Codex kontem ChatGPT.");
      const prompt = `Wyniki żądanych odczytów bieżących danych dla sessionId ${sessionId}: ${JSON.stringify(results)}. Kontynuuj dokładnie tę sesję i zwróć proposal zgodny z AgentResponse v1. Nie wykonuj zapisu.`;
      const result = await this.codex.runStructuredOrder(prompt, this.cwd, state.threadId);
      if (result.threadId !== state.threadId || result.response.sessionId !== sessionId || result.response.status !== "proposal")
        throw new Error("CODEX_PROTOCOL_ERROR: niezgodny threadId, sessionId lub status po wznowieniu.");
      const expected = new Map(results.flatMap(r => r.data.stocks.map(s => [s.productId, s.available] as const)));
      if (result.response.items.length !== state.response.items.length || result.response.items.some(item =>
        !state.response.items.some(before => before.productId === item.productId && before.quantity === item.quantity) ||
        item.available !== expected.get(item.productId)))
        throw new Error("CODEX_PROTOCOL_ERROR: propozycja nie zgadza się z wynikami odczytu.");
      state.response = result.response;
      return result.response;
    } finally { state.busy = false; }
  }

  async resumeWithChoice(sessionId: string, candidateId: string): Promise<AgentResponse> {
    const state = this.sessions.get(sessionId);
    if (!state) return this.error(sessionId, "SESSION_NOT_FOUND", "Nie znaleziono sesji.");
    if (state.busy) return this.error(sessionId, "SESSION_BUSY", "Sesja przetwarza już wybór.");
    if (state.response.status !== "needs_user_choice") return this.error(sessionId, "INVALID_STATE", "Sesja nie oczekuje na wybór.");
    const candidate = state.response.candidates.find(item => item.id === candidateId);
    if (!candidate) return this.error(sessionId, "INVALID_CHOICE", "Wybrany identyfikator nie jest kandydatem w tej sesji.");
    state.busy = true;
    try {
      if (!await this.auth()) return this.error(sessionId, "CHATGPT_AUTH_REQUIRED", "Zaloguj Codex kontem ChatGPT.");
      const prompt = `Użytkownik ręcznie wybrał kandydata ${JSON.stringify(candidate)} dla sessionId ${sessionId}. Kontynuuj tę samą sesję. Jeśli potrzebujesz aktualnego stanu, zwróć needs_data; nie zgaduj. Zwróć AgentResponse v1.`;
      const result = await this.codex.runStructuredOrder(prompt, this.cwd, state.threadId);
      if (result.threadId !== state.threadId || result.response.sessionId !== sessionId)
        throw new Error("CODEX_PROTOCOL_ERROR: niezgodny threadId lub sessionId po wyborze.");
      const response = result.response;
      const snapshot = this.catalog();
      if (response.recipient && !(response.recipient.kind === "person"
        ? snapshot.people.some(p => p.id === response.recipient!.id)
        : snapshot.shipyards.some(s => s.id === response.recipient!.id)))
        throw new Error("CODEX_PROTOCOL_ERROR: nieznany odbiorca po wyborze.");
      if (response.items.some(item => !snapshot.products.some(p => p.id === item.productId && !p.hidden)) ||
        response.needsData.some(req => req.arguments.productIds.some(id => !response.items.some(item => item.productId === id))))
        throw new Error("CODEX_PROTOCOL_ERROR: nieznany produkt po wyborze.");
      if (response.status === "proposal" && response.items.some(item => item.available == null))
        throw new Error("CODEX_PROTOCOL_ERROR: propozycja wymaga bieżącego stanu.");
      state.response = response;
      return response;
    } finally { state.busy = false; }
  }
}
