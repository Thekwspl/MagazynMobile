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

export interface OrderItemProposal {
  productId: string;
  label: string;
  quantity: number;
  unit: string;
  available?: number | null;
}

export interface ReadOnlyToolRequest {
  id: string;
  tool: "get_current_stock";
  arguments: { productIds: string[] };
}

export interface AgentResponse {
  schemaVersion: typeof PROTOCOL_VERSION;
  sessionId: string;
  status: AgentStatus;
  intent: "ORDER";
  recipient?: { id: string; label: string; kind: "person" | "shipyard" } | null;
  deliveryDate?: string | null;
  items: OrderItemProposal[];
  warnings: string[];
  questions: string[];
  candidates: Candidate[];
  needsData: ReadOnlyToolRequest[];
  error?: { code: string; message: string } | null;
}

export interface ReadOnlyToolResult {
  requestId: string;
  tool: string;
  data: { stocks: Array<{ productId: string; available: number }> };
}

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
    needsData: {
      type: "array", items: { type: "object", additionalProperties: false,
        required: ["id", "tool", "arguments"], properties: {
          id: { type: "string" }, tool: { enum: ["get_current_stock"] },
          arguments: { type: "object", additionalProperties: false,
            required: ["productIds"], properties: {
              productIds: { type: "array", items: { type: "string" } },
            } },
        } },
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
  properties?: Record<string, Schema>; items?: Schema };
const validate = (value: unknown, schema: Schema): boolean => {
  if (Array.isArray(schema.type)) {
    if (value === null && schema.type.includes("null")) return true;
    return validate(value, { ...schema, type: schema.type.find(type => type !== "null") });
  }
  if (schema.const !== undefined && value !== schema.const) return false;
  if (schema.enum && !schema.enum.includes(value)) return false;
  if (schema.type === "string") return typeof value === "string";
  if (schema.type === "number") return typeof value === "number" && Number.isFinite(value);
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
  return value as AgentResponse;
}
