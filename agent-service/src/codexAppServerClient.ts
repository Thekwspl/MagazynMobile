import { spawn } from "node:child_process";
import { createInterface } from "node:readline";
import { agentResponseJsonSchema, parseAgentResponse, type AgentResponse } from "./contracts.js";

interface RpcMessage {
  id?: number;
  result?: unknown;
  error?: { code: number; message: string };
  method?: string;
  params?: Record<string, unknown>;
}
export interface CodexTransport {
  write(message: string): void;
  onLine(callback: (line: string) => void): void;
  onExit(callback: (error: Error) => void): void;
  close(): void;
}
interface Pending {
  resolve(value: unknown): void;
  reject(error: Error): void;
  timer: ReturnType<typeof setTimeout>;
}
interface Collector {
  turnId?: string;
  events: RpcMessage[];
  finalText: string;
  toolCalls: number;
  resolve(text: string): void;
  reject(error: Error): void;
  timer: ReturnType<typeof setTimeout>;
}

export class CodexAppServerClient {
  private transport?: CodexTransport;
  private starting?: Promise<void>;
  private nextId = 1;
  private readonly pending = new Map<number, Pending>();
  private readonly collectors = new Map<string, Collector>();

  constructor(private readonly transportFactory?: () => CodexTransport,
    private readonly requestTimeout = 15000, private readonly turnTimeout = 120000) {}

  async start(command = "codex", mcp?: { script: string; url: string; token: string }): Promise<void> {
    if (this.starting) return this.starting;
    this.starting = this.initialize(command, mcp).catch(error => { this.close(); throw error; });
    return this.starting;
  }

  private async initialize(command: string, mcp?: { script: string; url: string; token: string }): Promise<void> {
    if (this.transportFactory) this.transport = this.transportFactory();
    else {
      const overrides = mcp ? [
        "-c", `mcp_servers.warehouse_catalog.command=${JSON.stringify(process.execPath)}`,
        "-c", `mcp_servers.warehouse_catalog.args=${JSON.stringify([mcp.script])}`,
        "-c", `mcp_servers.warehouse_catalog.env.WAREHOUSE_CATALOG_URL=${JSON.stringify(mcp.url)}`,
        "-c", `mcp_servers.warehouse_catalog.env.WAREHOUSE_CATALOG_TOKEN=${JSON.stringify(mcp.token)}`,
        "-c", "mcp_servers.warehouse_catalog.required=true",
      ] : [];
      const child = spawn(command, [...overrides, "app-server", "--listen", "stdio://"], { stdio: ["pipe", "pipe", "pipe"] });
      const stdout = createInterface({ input: child.stdout });
      // Drain stderr continuously; log only bounded generic diagnostics, never auth tokens or catalog data.
      child.stderr.on("data", () => { /* intentionally consumed */ });
      this.transport = {
        write: line => child.stdin.write(line),
        onLine: callback => stdout.on("line", callback),
        onExit: callback => {
          child.on("error", () => callback(new Error("Nie można uruchomić codex app-server.")));
          child.on("exit", code => callback(new Error(`codex app-server zakończył pracę (kod ${code ?? "brak"}).`)));
        },
        close: () => child.kill(),
      };
    }
    this.transport.onLine(line => this.receive(line));
    this.transport.onExit(error => {
      this.failAll(error);
      this.transport = undefined;
      this.starting = undefined;
    });
    await this.request("initialize", { clientInfo: {
      name: "magazyn_mobile_agent_service", title: "MagazynMobile Agent Service", version: "0.1.0",
    } });
    this.write({ method: "initialized", params: {} });
  }

  accountRead(): Promise<unknown> { return this.request("account/read", { refreshToken: false }); }
  startChatGptLogin(): Promise<unknown> { return this.request("account/login/start", { type: "chatgpt" }); }
  startChatGptDeviceLogin(): Promise<unknown> { return this.request("account/login/start", { type: "chatgptDeviceCode" }); }

  async runStructuredOrder(prompt: string, cwd: string, threadId?: string): Promise<{ threadId: string; response: AgentResponse; toolCalls: number }> {
    const thread = threadId
      ? await this.request<{ thread: { id: string } }>("thread/resume", { threadId })
      : await this.request<{ thread: { id: string } }>("thread/start", {
          cwd, approvalPolicy: "never", sandbox: "readOnly", serviceName: "magazyn_mobile_agent_service",
        });
    const id = thread.thread?.id;
    if (!id) throw new Error("CODEX_PROTOCOL_ERROR: brak threadId.");
    if (this.collectors.has(id)) throw new Error("CODEX_THREAD_BUSY: trwa już turn tej sesji.");
    let collector!: Collector;
    const completed = new Promise<string>((resolve, reject) => {
      collector = { events: [], finalText: "", toolCalls: 0, resolve, reject,
        timer: setTimeout(() => reject(new Error("CODEX_TURN_TIMEOUT: przekroczono czas oczekiwania na turn/completed.")), this.turnTimeout) };
    });
    void completed.catch(() => {});
    this.collectors.set(id, collector);
    try {
      const turn = await this.request<{ turn: { id: string } }>("turn/start", {
        threadId: id, input: [{ type: "text", text: prompt }], outputSchema: agentResponseJsonSchema,
      });
      if (!turn.turn?.id) throw new Error("CODEX_PROTOCOL_ERROR: brak turnId.");
      collector.turnId = turn.turn.id;
      for (const event of collector.events) this.handleEvent(collector, event);
      collector.events.length = 0;
      const finalText = await completed;
      if (!finalText.trim()) throw new Error("CODEX_PROTOCOL_ERROR: turn zakończył się bez AgentResponse.");
      return { threadId: id, response: parseAgentResponse(finalText), toolCalls: collector.toolCalls };
    } finally {
      clearTimeout(collector.timer);
      this.collectors.delete(id);
    }
  }

  close(): void {
    this.failAll(new Error("codex app-server zamknięty."));
    this.transport?.close();
    this.transport = undefined;
    this.starting = undefined;
  }

  private failAll(error: Error): void {
    for (const pending of this.pending.values()) { clearTimeout(pending.timer); pending.reject(error); }
    this.pending.clear();
    for (const collector of this.collectors.values()) collector.reject(error);
  }

  private request<T = unknown>(method: string, params: unknown): Promise<T> {
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        reject(new Error(`CODEX_REQUEST_TIMEOUT: ${method} nie odpowiedział w ${this.requestTimeout} ms.`));
      }, this.requestTimeout);
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer });
      try { this.write({ id, method, params }); }
      catch (error) { clearTimeout(timer); this.pending.delete(id); reject(error); }
    });
  }
  private write(message: unknown): void {
    if (!this.transport) throw new Error("codex app-server nie został uruchomiony.");
    this.transport.write(`${JSON.stringify(message)}\n`);
  }
  private receive(line: string): void {
    let message: RpcMessage;
    try { message = JSON.parse(line) as RpcMessage; } catch { return; }
    if (typeof message.id === "number") {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id); clearTimeout(pending.timer);
      if (message.error) pending.reject(new Error(`Codex ${message.error.code}: ${message.error.message}`));
      else pending.resolve(message.result);
      return;
    }
    if (!message.method || !["item/completed", "turn/completed"].includes(message.method)) return;
    const threadId = message.params?.threadId;
    if (typeof threadId !== "string") return;
    const collector = this.collectors.get(threadId);
    if (!collector) return;
    if (!collector.turnId) collector.events.push(message);
    else this.handleEvent(collector, message);
  }
  private handleEvent(collector: Collector, message: RpcMessage): void {
    const turn = message.params?.turn as { id?: string; status?: string; error?: { message?: string } } | undefined;
    const turnId = message.params?.turnId ?? turn?.id;
    if (turnId !== collector.turnId) return;
    if (message.method === "item/completed") {
      const item = message.params?.item as { type?: string; text?: string; phase?: string; server?: string; status?: string } | undefined;
      if (item?.type === "mcpToolCall" && item.server === "warehouse_catalog" && item.status === "completed") collector.toolCalls++;
      if (item?.type === "agentMessage" && typeof item.text === "string" && item.phase !== "commentary")
        collector.finalText = item.text;
    } else if (message.method === "turn/completed") {
      if (turn?.status !== "completed") collector.reject(new Error(`CODEX_TURN_FAILED: ${turn?.error?.message ?? turn?.status ?? "unknown"}`));
      else collector.resolve(collector.finalText);
    }
  }
}
