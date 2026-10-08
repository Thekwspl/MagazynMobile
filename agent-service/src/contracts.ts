export const PROTOCOL_VERSION = 1 as const;

export type AgentStatus =
  | "needs_data"
  | "needs_user_choice"
  | "proposal"
  | "error";

export interface Candidate {
  id: string;
  label: string;
  kind: "person" | "product" | "shipyard" | "task_place";
}

export type ClarificationType = "choice" | "yes_no" | "text";

export interface ClarificationQuestion {
  id: string;
  question: string;
  type: ClarificationType;
  candidates: Candidate[];
  required: boolean;
}

export interface ClarificationAnswer {
  questionId: string;
  candidateId?: string;
  text?: string;
}

export interface ResolvedClarificationAnswer {
  questionId: string;
  question: string;
  answer: string;
  candidate?: Candidate;
}

export interface OrderItemProposal {
  productId: string;
  label: string;
  quantity: number;
  unit: string;
  available?: number | null;
}

export type RecipientKey = { recipientKind: "person"; recipientId: string } | { recipientKind: "shipyard"; recipientId: string };
export type ReadOnlyToolRequest =
  | { id: string; tool: "get_current_stock"; arguments: { productIds: string[] } }
  | { id: string; tool: "get_person_current_items"; arguments: { personId: string } }
  | { id: string; tool: "get_shipyard_stock"; arguments: { shipyardId: string } }
  | { id: string; tool: "get_active_orders"; arguments: RecipientKey }
  | { id: string; tool: "get_recent_issues"; arguments: RecipientKey & { limit: number } };

export interface AgentResponse {
  schemaVersion: typeof PROTOCOL_VERSION;
  sessionId: string;
  status: AgentStatus;
  intent: "ORDER";
  recipient?: { id: string; label: string; kind: "person" | "shipyard" } | null;
  deliveryDate?: string | null;
  items: OrderItemProposal[];
  warnings: string[];
  /** @deprecated Compatibility only. Use clarifications. */
  questions: string[];
  /** @deprecated Compatibility only. Use clarifications[].candidates. */
  candidates: Candidate[];
  clarifications: ClarificationQuestion[];
  needsData: ReadOnlyToolRequest[];
  error?: { code: string; message: string } | null;
}

export type ReadOnlyToolResult =
  | { requestId: string; tool: "get_current_stock"; data: { stocks: Array<{ productId: string; available: number }> } }
  | { requestId: string; tool: "get_person_current_items"; data: { personId: string; items: Array<{ productId: string; quantity: number; unit: string; issuedDate: string }> } }
  | { requestId: string; tool: "get_shipyard_stock"; data: { shipyardId: string; stocks: Array<{ productId: string; quantity: number; unit: string }> } }
  | { requestId: string; tool: "get_active_orders"; data: RecipientKey & { orders: Array<{ orderId: string; status: string; plannedIssueDate: string; items: Array<{ productId: string; quantity: number; unit: string }> }> } }
  | { requestId: string; tool: "get_recent_issues"; data: RecipientKey & { issues: Array<{ movementId: string; lineId: string; productId: string; quantity: number; unit: string; issuedDate: string }> } };

export const agentResponseJsonSchema = {
  type: "object",
  additionalProperties: false,
  required: [
    "schemaVersion",
    "sessionId",
    "status",
    "intent",
    "items",
    "warnings",
    "questions",
    "candidates",
    "clarifications",
    "needsData",
    "recipient",
    "deliveryDate",
    "error",
  ],
  properties: {
    schemaVersion: { enum: [PROTOCOL_VERSION] },
    sessionId: { type: "string" },
    status: {
      enum: ["needs_data", "needs_user_choice", "proposal", "error"],
    },
    intent: { enum: ["ORDER"] },
    recipient: {
      type: ["object", "null"],
      additionalProperties: false,
      required: ["id", "label", "kind"],
      properties: {
        id: { type: "string" },
        label: { type: "string" },
        kind: { enum: ["person", "shipyard"] },
      },
    },
    deliveryDate: { type: ["string", "null"] },
    items: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        properties: {
          productId: { type: "string" },
          label: { type: "string" },
          quantity: { type: "number" },
          unit: { type: "string" },
          available: { type: ["number", "null"] },
        },
        required: ["productId", "label", "quantity", "unit", "available"],
      },
    },
    warnings: { type: "array", items: { type: "string" } },
    questions: { type: "array", items: { type: "string" } },
    candidates: {
      type: "array", items: { type: "object", additionalProperties: false,
        required: ["id", "label", "kind"], properties: {
          id: { type: "string" }, label: { type: "string" },
          kind: { enum: ["person", "product", "shipyard", "task_place"] },
        } },
    },
    clarifications: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["id", "question", "type", "candidates", "required"],
        properties: {
          id: { type: "string" },
          question: { type: "string" },
          type: { enum: ["choice", "yes_no", "text"] },
          candidates: {
            type: "array",
            items: {
              type: "object",
              additionalProperties: false,
              required: ["id", "label", "kind"],
              properties: {
                id: { type: "string" },
                label: { type: "string" },
                kind: { enum: ["person", "product", "shipyard", "task_place"] },
              },
            },
          },
          required: { type: "boolean" },
        },
      },
    },
    needsData: {
      type: "array",
      items: {
        anyOf: [
          {
            type: "object",
            additionalProperties: false,
            required: ["id", "tool", "arguments"],
            properties: {
              id: { type: "string" },
              tool: { enum: ["get_current_stock"] },
              arguments: {
                type: "object",
                additionalProperties: false,
                required: ["productIds"],
                properties: {
                  productIds: { type: "array", items: { type: "string" } },
                },
              },
            },
          },
          {
            type: "object",
            additionalProperties: false,
            required: ["id", "tool", "arguments"],
            properties: {
              id: { type: "string" },
              tool: { enum: ["get_person_current_items"] },
              arguments: {
                type: "object",
                additionalProperties: false,
                required: ["personId"],
                properties: {
                  personId: { type: "string" },
                },
              },
            },
          },
          {
            type: "object",
            additionalProperties: false,
            required: ["id", "tool", "arguments"],
            properties: {
              id: { type: "string" },
              tool: { enum: ["get_shipyard_stock"] },
              arguments: {
                type: "object",
                additionalProperties: false,
                required: ["shipyardId"],
                properties: {
                  shipyardId: { type: "string" },
                },
              },
            },
          },
          {
            type: "object",
            additionalProperties: false,
            required: ["id", "tool", "arguments"],
            properties: {
              id: { type: "string" },
              tool: { enum: ["get_active_orders"] },
              arguments: {
                type: "object",
                additionalProperties: false,
                required: ["recipientKind", "recipientId"],
                properties: {
                  recipientKind: { enum: ["person", "shipyard"] },
                  recipientId: { type: "string" },
                },
              },
            },
          },
          {
            type: "object",
            additionalProperties: false,
            required: ["id", "tool", "arguments"],
            properties: {
              id: { type: "string" },
              tool: { enum: ["get_recent_issues"] },
              arguments: {
                type: "object",
                additionalProperties: false,
                required: ["recipientKind", "recipientId", "limit"],
                properties: {
                  recipientKind: { enum: ["person", "shipyard"] },
                  recipientId: { type: "string" },
                  limit: { type: "number" },
                },
              },
            },
          },
        ],
      },
    },
    error: { type: ["object", "null"], additionalProperties: false,
      required: ["code", "message"], properties: {
        code: { type: "string" }, message: { type: "string" },
      } },
  },
} as const;

// Validate the entire model response at the trust boundary, including nested objects.
type Schema = { type?: string | readonly string[]; const?: unknown; enum?: readonly unknown[];
  required?: readonly string[]; additionalProperties?: boolean;
  properties?: Record<string, Schema>; items?: Schema; anyOf?: readonly Schema[] };
const validate = (value: unknown, schema: Schema): boolean => {
  if (schema.anyOf) return schema.anyOf.some(candidate => validate(value, candidate));
  if (Array.isArray(schema.type)) {
    if (value === null && schema.type.includes("null")) return true;
    return validate(value, { ...schema, type: schema.type.find(type => type !== "null") });
  }
  if (schema.const !== undefined && value !== schema.const) return false;
  if (schema.enum && !schema.enum.includes(value)) return false;
  if (schema.type === "string") return typeof value === "string";
  if (schema.type === "number") return typeof value === "number" && Number.isFinite(value);
  if (schema.type === "boolean") return typeof value === "boolean";
  if (schema.type === "array") return Array.isArray(value) && value.every((item) => validate(item, schema.items!));
  if (schema.type === "object") {
    if (!value || typeof value !== "object" || Array.isArray(value)) return false;
    const record = value as Record<string, unknown>;
    if (schema.required?.some((key) => !(key in record))) return false;
    if (schema.additionalProperties === false && Object.keys(record).some((key) => !(key in (schema.properties ?? {})))) return false;
    return Object.entries(record).every(([key, item]) => validate(item, schema.properties![key]));
  }
  return true;
};

export function parseAgentResponse(text: string): AgentResponse {
  let value: unknown;
  try { value = JSON.parse(text); } catch { throw new Error("CODEX_PROTOCOL_ERROR: odpowiedź nie jest JSON."); }
  if (!validate(value, agentResponseJsonSchema))
    throw new Error("CODEX_PROTOCOL_ERROR: odpowiedź nie spełnia schematu AgentResponse v1.");
  const parsed = value as AgentResponse;
  if (parsed.needsData.length > 4 || new Set(parsed.needsData.map(r => r.id)).size !== parsed.needsData.length ||
    parsed.needsData.some(r => !validToolRequest(r)))
    throw new Error("CODEX_PROTOCOL_ERROR: nieprawidłowe żądanie odczytu.");
  if (!validClarifications(parsed))
    throw new Error("CODEX_PROTOCOL_ERROR: nieprawidłowy zestaw pytań doprecyzowujących.");
  return value as AgentResponse;
}

const object = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const shape = (value: unknown, keys: string[]): value is Record<string, unknown> =>
  object(value) && Object.keys(value).length === keys.length && keys.every(k => k in value);
const id = (value: unknown): value is string => typeof value === "string" && value.trim().length > 0 && value.length <= 128;
const nonBlank = (value: unknown, max: number): value is string =>
  typeof value === "string" && value.trim().length > 0 && value.length <= max;
const quantity = (value: unknown, positive = false): value is number =>
  typeof value === "number" && Number.isFinite(value) && (!positive || value > 0);
const recipient = (value: Record<string, unknown>): boolean =>
  (value.recipientKind === "person" || value.recipientKind === "shipyard") && id(value.recipientId);

function validClarifications(response: AgentResponse): boolean {
  const questions = response.clarifications;
  if (questions.length > 20 || new Set(questions.map(question => question.id)).size !== questions.length) return false;
  if ((response.status === "needs_user_choice") !== (questions.length > 0)) return false;
  return questions.every(question => {
    if (!id(question.id) || !nonBlank(question.question, 1000) || typeof question.required !== "boolean") return false;
    if (question.type === "choice") return question.candidates.length > 0 &&
      question.candidates.length <= 100 && new Set(question.candidates.map(candidate => candidate.id)).size === question.candidates.length &&
      question.candidates.every(candidate => id(candidate.id) && nonBlank(candidate.label, 500));
    return (question.type === "yes_no" || question.type === "text") && question.candidates.length === 0;
  });
}

export function resolveClarificationAnswers(questions: ClarificationQuestion[], value: unknown):
  { ok: true; answers: ResolvedClarificationAnswer[] } | { ok: false; message: string } {
  if (!Array.isArray(value)) return { ok: false, message: "Odpowiedzi muszą być tablicą." };
  const raw = value as unknown[];
  const records = raw.filter(object);
  if (records.length !== raw.length) return { ok: false, message: "Niepoprawny format odpowiedzi." };
  const ids = records.map(answer => answer.questionId);
  if (new Set(ids).size !== ids.length) return { ok: false, message: "Każde pytanie może mieć tylko jedną odpowiedź." };
  const resolved: ResolvedClarificationAnswer[] = [];
  for (const answer of records) {
    if (!id(answer.questionId)) return { ok: false, message: "Niepoprawne ID pytania." };
    const question = questions.find(item => item.id === answer.questionId);
    if (!question) return { ok: false, message: "Pytanie nie należy do aktualnej rundy." };
    if (question.type === "choice") {
      if (!shape(answer, ["questionId", "candidateId"]) || !id(answer.candidateId))
        return { ok: false, message: "Pytanie wyboru wymaga kandydata." };
      const candidate = question.candidates.find(item => item.id === answer.candidateId);
      if (!candidate) return { ok: false, message: "Kandydat nie należy do wskazanego pytania." };
      resolved.push({ questionId: question.id, question: question.question, answer: candidate.label, candidate });
    } else {
      if (!shape(answer, ["questionId", "text"]) || !nonBlank(answer.text, 2000))
        return { ok: false, message: "Pytanie wymaga odpowiedzi tekstowej." };
      resolved.push({ questionId: question.id, question: question.question, answer: answer.text.trim() });
    }
  }
  if (questions.some(question => question.required && !resolved.some(answer => answer.questionId === question.id)))
    return { ok: false, message: "Brakuje odpowiedzi na wymagane pytanie." };
  return { ok: true, answers: resolved };
}

export function validToolRequest(value: unknown): value is ReadOnlyToolRequest {
  if (!shape(value, ["id", "tool", "arguments"]) || !id(value.id) || !object(value.arguments)) return false;
  const args = value.arguments;
  switch (value.tool) {
    case "get_current_stock": return shape(args, ["productIds"]) && Array.isArray(args.productIds) &&
      args.productIds.length > 0 && args.productIds.length <= 20 &&
      new Set(args.productIds).size === args.productIds.length && args.productIds.every(id);
    case "get_person_current_items": return shape(args, ["personId"]) && id(args.personId);
    case "get_shipyard_stock": return shape(args, ["shipyardId"]) && id(args.shipyardId);
    case "get_active_orders": return shape(args, ["recipientKind", "recipientId"]) && recipient(args);
    case "get_recent_issues": return shape(args, ["recipientKind", "recipientId", "limit"]) &&
      recipient(args) && Number.isInteger(args.limit) && (args.limit as number) >= 1 && (args.limit as number) <= 20;
    default: return false;
  }
}

export function validToolResult(value: unknown, request: ReadOnlyToolRequest): value is ReadOnlyToolResult {
  if (!shape(value, ["requestId", "tool", "data"]) || value.requestId !== request.id ||
    value.tool !== request.tool || !object(value.data)) return false;
  const data = value.data;
  const entries = (array: unknown, max: number, fields: string[], predicate: (entry: Record<string, unknown>) => boolean): boolean =>
    Array.isArray(array) && array.length <= max && array.every(entry => shape(entry, fields) && predicate(entry));
  switch (request.tool) {
    case "get_current_stock": return shape(data, ["stocks"]) && entries(data.stocks, 20, ["productId", "available"],
      e => id(e.productId) && quantity(e.available)) && (data.stocks as Array<{productId:string}>).length === request.arguments.productIds.length &&
      new Set((data.stocks as Array<{productId:string}>).map(s => s.productId)).size === request.arguments.productIds.length &&
      (data.stocks as Array<{productId:string}>).every(s => request.arguments.productIds.includes(s.productId));
    case "get_person_current_items": return shape(data, ["personId", "items"]) && data.personId === request.arguments.personId &&
      entries(data.items, 100, ["productId", "quantity", "unit", "issuedDate"], e => id(e.productId) && quantity(e.quantity, true) && id(e.unit) && id(e.issuedDate));
    case "get_shipyard_stock": return shape(data, ["shipyardId", "stocks"]) && data.shipyardId === request.arguments.shipyardId &&
      entries(data.stocks, 100, ["productId", "quantity", "unit"], e => id(e.productId) && quantity(e.quantity) && id(e.unit));
    case "get_active_orders": return shape(data, ["recipientKind", "recipientId", "orders"]) &&
      data.recipientKind === request.arguments.recipientKind && data.recipientId === request.arguments.recipientId &&
      entries(data.orders, 20, ["orderId", "status", "plannedIssueDate", "items"], e =>
        id(e.orderId) && id(e.status) && id(e.plannedIssueDate) && entries(e.items, 30, ["productId", "quantity", "unit"],
          line => id(line.productId) && quantity(line.quantity, true) && id(line.unit)));
    case "get_recent_issues": return shape(data, ["recipientKind", "recipientId", "issues"]) &&
      data.recipientKind === request.arguments.recipientKind && data.recipientId === request.arguments.recipientId &&
      entries(data.issues, request.arguments.limit, ["movementId", "lineId", "productId", "quantity", "unit", "issuedDate"], e =>
        id(e.movementId) && id(e.lineId) && id(e.productId) && quantity(e.quantity, true) && id(e.unit) && id(e.issuedDate));
  }
}
