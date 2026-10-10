import assert from "node:assert/strict";
import test from "node:test";
import { CatalogStore } from "../src/catalog.js";
import { CodexWarehouseAgent, type CodexRunner } from "../src/codexWarehouseAgent.js";
import { parseAgentResponse, type AgentResponse, type ClarificationQuestion } from "../src/contracts.js";

const catalog = new CatalogStore();
catalog.fullSync({
  revision: 1,
  people: [{ id: "p1", firstName: "Jan", lastName: "Kowalski" }],
  products: [
    { id: "g", name: "Rękawice Monterskie", variant: "10", unit: "opak." },
    { id: "h", name: "Kask Żółty", unit: "szt." },
  ],
  shipyards: [],
  taskPlaces: [],
});

const choice = (id: string, question: string, candidates = [
  { id: "g", label: "Rękawice Monterskie 10", kind: "product" as const },
]): ClarificationQuestion => ({ id, question, type: "choice", candidates, required: true });
const yesNo = (id: string, question: string): ClarificationQuestion =>
  ({ id, question, type: "yes_no", candidates: [], required: true });
const text = (id: string, question: string): ClarificationQuestion =>
  ({ id, question, type: "text", candidates: [], required: true });

const response = (sessionId: string, status: AgentResponse["status"], clarifications: ClarificationQuestion[] = []): AgentResponse => ({
  schemaVersion: 2,
  sessionId,
  status,
  intent: "ORDER", task: null, note: null, contact: null,
  recipient: { id: "p1", label: "Jan Kowalski", kind: "person" },
  deliveryDate: "2026-10-09",
  items: [{ productId: "g", label: "Rękawice Monterskie 10", quantity: 2, unit: "opak.",
    available: status === "proposal" ? 3 : null }],
  warnings: [],
  questions: clarifications.map(item => item.question),
  candidates: clarifications.flatMap(item => item.candidates),
  clarifications,
  needsData: status === "needs_data" ? [{ id: "stock-round", tool: "get_current_stock", arguments: { productIds: ["g"] } }] : [],
  error: null,
});

class ScriptedRunner implements CodexRunner {
  readonly calls: Array<{ prompt: string; threadId?: string }> = [];
  constructor(private readonly steps: Array<(sessionId: string) => AgentResponse>) {}
  async start(): Promise<void> {}
  async accountRead(): Promise<unknown> { return { account: { type: "chatgpt" } }; }
  async runStructuredOrder(prompt: string, _cwd: string, threadId?: string) {
    this.calls.push({ prompt, threadId });
    const sessionId = prompt.match(/sessionId[: ]+([\da-f-]{36})/)?.[1] ?? "";
    const step = this.steps.shift();
    if (!step) throw new Error("unexpected turn");
    return { threadId: threadId ?? `thread-${sessionId}`, response: step(sessionId), toolCalls: 1 };
  }
}

const agentWith = (...steps: Array<(sessionId: string) => AgentResponse>) => {
  const runner = new ScriptedRunner(steps);
  return {
    runner,
    agent: new CodexWarehouseAgent(runner, () => ({ script: "mcp", url: "local", token: "secret" }), () => catalog.read()),
  };
};

test("structured clarification schema supports choice, yes_no, text, mixed questions and separate candidate lists", () => {
  for (const question of [choice("q-choice", "Który kask?"), yesNo("q-confirm", "Czy chodzi o dwa opakowania?"), text("q-size", "Jaki rozmiar?")]) {
    const parsed = parseAgentResponse(JSON.stringify(response("s", "needs_user_choice", [question])));
    assert.equal(parsed.clarifications[0].type, question.type);
  }
  const mixed = parseAgentResponse(JSON.stringify(response("s", "needs_user_choice", [
    choice("q-gloves", "Które rękawice?"),
    choice("q-helmet", "Który kask?", [{ id: "h", label: "Kask Żółty", kind: "product" }]),
    yesNo("q-count", "Czy chodzi o dwa opakowania?"),
    text("q-size", "Jaki rozmiar?"),
  ])));
  assert.deepEqual(mixed.clarifications[0].candidates.map(item => item.id), ["g"]);
  assert.deepEqual(mixed.clarifications[1].candidates.map(item => item.id), ["h"]);
});

test("batch validation rejects missing, cross-question, unknown, duplicate and stale answers", async () => {
  const round1 = [
    choice("q-gloves", "Które rękawice?"),
    choice("q-helmet", "Który kask?", [{ id: "h", label: "Kask Żółty", kind: "product" }]),
    yesNo("q-count", "Czy chodzi o dwa opakowania?"),
    text("q-size", "Jaki rozmiar?"),
  ];
  const round2 = [yesNo("q-final", "Czy wszystko się zgadza?")];
  const { agent, runner } = agentWith(
    sessionId => response(sessionId, "needs_user_choice", round1),
    sessionId => response(sessionId, "needs_user_choice", round2),
  );
  try {
    const initial = await agent.start("Kowalski jutro 2 rękawice Monterskie 10 i kask");
    assert.equal((await agent.resumeWithAnswers(initial.sessionId, [])).error?.code, "INVALID_ANSWERS");
    assert.equal((await agent.resumeWithAnswers(initial.sessionId, [
      { questionId: "q-gloves", candidateId: "h" },
      { questionId: "q-helmet", candidateId: "h" },
      { questionId: "q-count", text: "Tak" },
      { questionId: "q-size", text: "10" },
    ])).error?.code, "INVALID_ANSWERS");
    assert.equal((await agent.resumeWithAnswers(initial.sessionId, [{ questionId: "unknown", text: "Tak" }])).error?.code, "INVALID_ANSWERS");
    assert.equal((await agent.resumeWithAnswers(initial.sessionId, [
      { questionId: "q-gloves", candidateId: "g" }, { questionId: "q-gloves", candidateId: "g" },
    ])).error?.code, "INVALID_ANSWERS");
    assert.equal(runner.calls.length, 1);

    const next = await agent.resumeWithAnswers(initial.sessionId, [
      { questionId: "q-gloves", candidateId: "g" },
      { questionId: "q-helmet", candidateId: "h" },
      { questionId: "q-count", text: "Tak" },
      { questionId: "q-size", text: "10" },
    ]);
    assert.equal(next.clarifications[0].id, "q-final");
    assert.equal(runner.calls.length, 2);
    assert.equal((await agent.resumeWithAnswers(initial.sessionId, [
      { questionId: "q-gloves", candidateId: "g" },
    ])).error?.code, "INVALID_ANSWERS");
    assert.equal(runner.calls.length, 2);
  } finally { agent.close(); }
});

test("all answers resume one thread once, continue through data and a second question round, then propose", async () => {
  const round1 = [choice("q-helmet", "Który kask?", [{ id: "h", label: "Kask Żółty", kind: "product" }]),
    yesNo("q-count", "Czy chodzi o 2 opakowania rękawic?"), text("q-size", "Jaki rozmiar?")];
  const round2 = [yesNo("q-confirm", "Czy przygotować propozycję?")];
  const { agent, runner } = agentWith(
    sessionId => response(sessionId, "needs_user_choice", round1),
    sessionId => response(sessionId, "needs_data"),
    sessionId => response(sessionId, "needs_user_choice", round2),
    sessionId => response(sessionId, "proposal"),
  );
  try {
    const initial = await agent.start("Kowalski jutro 2 rękawice Monterskie 10 i kask");
    const needsData = await agent.resumeWithAnswers(initial.sessionId, [
      { questionId: "q-helmet", candidateId: "h" },
      { questionId: "q-count", text: "Tak" },
      { questionId: "q-size", text: "10" },
    ]);
    assert.equal(needsData.status, "needs_data");
    assert.equal(runner.calls.length, 2);
    assert.match(runner.calls[1].prompt, /q-helmet/);
    assert.match(runner.calls[1].prompt, /q-count/);
    assert.match(runner.calls[1].prompt, /q-size/);

    const secondRound = await agent.resumeWithData(initial.sessionId, [{ requestId: "stock-round", tool: "get_current_stock",
      data: { stocks: [{ productId: "g", available: 3 }] } }]);
    assert.equal(secondRound.status, "needs_user_choice");
    const proposal = await agent.resumeWithAnswers(initial.sessionId, [{ questionId: "q-confirm", text: "Tak" }]);
    assert.equal(proposal.status, "proposal");
    assert.equal(proposal.sessionId, initial.sessionId);
    assert.equal(runner.calls.length, 4);
    assert.ok(runner.calls.slice(1).every(call => call.threadId === `thread-${initial.sessionId}`));
  } finally { agent.close(); }
});
