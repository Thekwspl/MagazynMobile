import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";
import { createInterface } from "node:readline";
import { agentResponseJsonSchema, type AgentResponse } from "./contracts.js";

interface JsonRpcResponse {
  id?: number;
  result?: unknown;
  error?: { code: number; message: string };
  method?: string;
  params?: Record<string, unknown>;
}
interface PendingRequest {
  resolve: (value: unknown) => void;
  reject: (reason: Error) => void;
}

export class CodexAppServerClient {
  private process?: ChildProcessWithoutNullStreams;
  private nextId = 1;
  private readonly pending = new Map<number, PendingRequest>();
  private readonly listeners = new Map<string, Set<(params: Record<string, unknown>) => void>>();

  async start(command = "codex"): Promise<void> {
    if (this.process) return;
    this.process = spawn(command, ["app-server", "--listen", "stdio://"], {
      stdio: ["pipe", "pipe", "pipe"],
    });
    this.process.once("exit", (code) => {
      const error = new Error(`codex app-server zakończył pracę (kod ${code ?? "brak"}).`);
      for (const request of this.pending.values()) request.reject(error);
      this.pending.clear();
      this.process = undefined;
    });
    const lines = createInterface({ input: this.process.stdout });
    lines.on("line", (line) => this.receive(line));

    await this.request("initialize", {
      clientInfo: {
        name: "magazyn_mobile_agent_service",
        title: "MagazynMobile Agent Service",
        version: "0.1.0",
      },
    });
    this.notify("initialized", {});
  }

  accountRead(): Promise<unknown> {
    return this.request("account/read", { refreshToken: false });
  }

  startChatGptDeviceLogin(): Promise<unknown> {
    return this.request("account/login/start", { type: "chatgptDeviceCode" });
  }

  async runStructuredOrder(
    prompt: string,
    cwd: string,
    threadId?: string,
  ): Promise<{ threadId: string; response: AgentResponse }> {
    const thread = threadId
      ? await this.request<{ thread: { id: string } }>("thread/resume", { threadId })
      : await this.request<{ thread: { id: string } }>("thread/start", {
          cwd,
          approvalPolicy: "never",
          sandbox: "readOnly",
        });
    const id = thread.thread.id;
    let finalText = "";
    let expectedTurnId: string | undefined;
    const completed = new Promise<void>((resolve, reject) => {
      const removeItem = this.on("item/completed", (params) => {
        const item = params.item as { type?: string; text?: string } | undefined;
        if (item?.type === "agentMessage" && typeof item.text === "string") finalText = item.text;
      });
      const removeTurn = this.on("turn/completed", (params) => {
        const turn = params.turn as { id?: string; status?: string; error?: { message?: string } } | undefined;
        if (expectedTurnId && turn?.id !== expectedTurnId) return;
        removeItem();
        removeTurn();
        if (turn?.status === "failed") reject(new Error(turn.error?.message ?? "Codex turn failed"));
        else resolve();
      });
    });
    const turn = await this.request<{ turn: { id: string } }>("turn/start", {
      threadId: id,
      input: [{ type: "text", text: prompt }],
      outputSchema: agentResponseJsonSchema,
    });
    expectedTurnId = turn.turn.id;
    await completed;
    return { threadId: id, response: JSON.parse(finalText) as AgentResponse };
  }

  close(): void {
    this.process?.kill();
    this.process = undefined;
  }

  private request<T = unknown>(method: string, params: unknown): Promise<T> {
    const id = this.nextId++;
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject });
      this.write({ id, method, params });
    });
  }

  private notify(method: string, params: unknown): void {
    this.write({ method, params });
  }

  private write(message: unknown): void {
    if (!this.process) throw new Error("codex app-server nie został uruchomiony");
    this.process.stdin.write(`${JSON.stringify(message)}\n`);
  }

  private receive(line: string): void {
    let message: JsonRpcResponse;
    try {
      message = JSON.parse(line) as JsonRpcResponse;
    } catch {
      return;
    }
    if (typeof message.id === "number") {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      if (message.error) pending.reject(new Error(message.error.message));
      else pending.resolve(message.result);
      return;
    }
    if (message.method) {
      for (const listener of this.listeners.get(message.method) ?? []) listener(message.params ?? {});
    }
  }

  private on(method: string, listener: (params: Record<string, unknown>) => void): () => void {
    const listeners = this.listeners.get(method) ?? new Set();
    listeners.add(listener);
    this.listeners.set(method, listeners);
    return () => listeners.delete(listener);
  }
}
