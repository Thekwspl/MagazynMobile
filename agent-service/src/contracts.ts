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
  available?: number;
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
  recipient?: { id: string; label: string; kind: "person" | "shipyard" };
  deliveryDate?: string;
  items: OrderItemProposal[];
  warnings: string[];
  questions: string[];
  candidates: Candidate[];
  needsData: ReadOnlyToolRequest[];
  error?: { code: string; message: string };
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
  ],
  properties: {
    schemaVersion: { const: PROTOCOL_VERSION },
    sessionId: { type: "string" },
    status: {
      enum: ["needs_data", "needs_user_choice", "proposal", "error"],
    },
    intent: { const: "ORDER" },
    recipient: {
      type: "object",
      additionalProperties: false,
      required: ["id", "label", "kind"],
      properties: {
        id: { type: "string" },
        label: { type: "string" },
        kind: { enum: ["person", "shipyard"] },
      },
    },
    deliveryDate: { type: "string" },
    items: {
      type: "array",
      items: {
        type: "object",
        additionalProperties: false,
        required: ["productId", "label", "quantity", "unit"],
        properties: {
          productId: { type: "string" },
          label: { type: "string" },
          quantity: { type: "number" },
          unit: { type: "string" },
          available: { type: "number" },
        },
      },
    },
    warnings: { type: "array", items: { type: "string" } },
    questions: { type: "array", items: { type: "string" } },
    candidates: { type: "array", items: { type: "object" } },
    needsData: { type: "array", items: { type: "object" } },
    error: { type: "object" },
  },
} as const;
