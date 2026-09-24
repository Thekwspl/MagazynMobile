import { randomUUID } from "node:crypto";
import type { CatalogSnapshot, PersonRecord, ProductRecord } from "./catalog.js";
import {
  PROTOCOL_VERSION,
  type AgentResponse,
  type OrderItemProposal,
  type ReadOnlyToolResult,
} from "./contracts.js";

interface SessionState {
  response: AgentResponse;
}

const normalize = (value: string): string =>
  value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase("pl-PL");

const labelsForPerson = (person: PersonRecord): string[] => [
  `${person.firstName} ${person.lastName}`,
  person.lastName,
  ...(person.aliases ?? []),
];

const labelsForProduct = (product: ProductRecord): string[] => [
  product.name,
  `${product.name} ${product.variant ?? ""}`.trim(),
  ...(product.aliases ?? []),
];

const productLabel = (product: ProductRecord): string =>
  [product.name, product.variant].filter(Boolean).join(" — ");

export class WarehouseAgent {
  private readonly sessions = new Map<string, SessionState>();

  constructor(
    private readonly catalog: () => CatalogSnapshot,
    private readonly now: () => Date = () => new Date(),
  ) {}

  start(message: string): AgentResponse {
    const sessionId = randomUUID();
    const response = this.resolve(sessionId, message);
    this.sessions.set(sessionId, { response });
    return structuredClone(response);
  }

  resumeWithData(sessionId: string, results: ReadOnlyToolResult[]): AgentResponse {
    const state = this.sessions.get(sessionId);
    if (!state) return this.error(sessionId, "SESSION_NOT_FOUND", "Nie znaleziono sesji.");
    if (state.response.status !== "needs_data") {
      return this.error(sessionId, "INVALID_STATE", "Sesja nie oczekuje na dane.");
    }

    const requested = new Set(state.response.needsData.map((request) => request.id));
    const stockByProduct = new Map<string, number>();
    for (const result of results) {
      if (!requested.has(result.requestId) || result.tool !== "get_current_stock") {
        return this.error(sessionId, "TOOL_NOT_ALLOWED", "Dozwolone są wyłącznie żądane odczyty magazynowe.");
      }
      for (const stock of result.data.stocks) stockByProduct.set(stock.productId, stock.available);
    }

    const missing = state.response.items.filter((item) => !stockByProduct.has(item.productId));
    if (missing.length > 0) {
      return this.error(sessionId, "INCOMPLETE_DATA", "Brakuje wyniku odczytu dla części produktów.");
    }

    const items = state.response.items.map((item) => ({
      ...item,
      available: stockByProduct.get(item.productId),
    }));
    const warnings = [...state.response.warnings];
    for (const item of items) {
      if ((item.available ?? 0) < item.quantity) {
        warnings.push(`Niewystarczający stan: ${item.label}.`);
      }
    }
    const response: AgentResponse = {
      ...state.response,
      status: "proposal",
      items,
      warnings,
      needsData: [],
    };
    this.sessions.set(sessionId, { response });
    return structuredClone(response);
  }

  private resolve(sessionId: string, message: string): AgentResponse {
    const catalog = this.catalog();
    const normalized = normalize(message);
    const people = catalog.people.filter((person) =>
      labelsForPerson(person).some((label) => normalized.includes(normalize(label))),
    );
    const shipyards = catalog.shipyards.filter((shipyard) =>
      [shipyard.name, ...(shipyard.aliases ?? [])].some((label) =>
        normalized.includes(normalize(label)),
      ),
    );
    const base = this.base(sessionId);

    if (people.length + shipyards.length > 1) {
      return {
        ...base,
        status: "needs_user_choice",
        questions: ["Którą osobę masz na myśli?"],
        candidates: [
          ...people.map((person) => ({
            id: person.id,
            label: `${person.firstName} ${person.lastName}`,
            kind: "person" as const,
          })),
          ...shipyards.map((shipyard) => ({
            id: shipyard.id,
            label: shipyard.name,
            kind: "shipyard" as const,
          })),
        ],
      };
    }
    if (people.length + shipyards.length === 0) {
      return {
        ...base,
        status: "needs_user_choice",
        questions: ["Nie rozpoznano odbiorcy. Wybierz osobę lub stocznię."],
        candidates: catalog.people.map((person) => ({
          id: person.id,
          label: `${person.firstName} ${person.lastName}`,
          kind: "person",
        })),
      };
    }

    const productMatches = catalog.products
      .filter((product) => !product.hidden)
      .map((product) => ({
        product,
        label: labelsForProduct(product)
          .map(normalize)
          .filter((label) => normalized.includes(label))
          .sort((a, b) => b.length - a.length)[0],
      }))
      .filter((match): match is { product: ProductRecord; label: string } => Boolean(match.label));
    const ambiguousLabel = productMatches.find(
      (match, index, matches) =>
        matches.findIndex((candidate) => candidate.label === match.label) !== index,
    )?.label;
    if (ambiguousLabel) {
      const candidates = productMatches
        .filter((match) => match.label === ambiguousLabel)
        .map(({ product }) => ({
          id: product.id,
          label: productLabel(product),
          kind: "product" as const,
        }));
      return {
        ...base,
        status: "needs_user_choice",
        questions: ["Który produkt lub wariant masz na myśli?"],
        candidates,
      };
    }
    const matchedProducts = productMatches.map(({ product }) => product);
    if (matchedProducts.length === 0) {
      return {
        ...base,
        status: "needs_user_choice",
        recipient: {
          id: people[0]?.id ?? shipyards[0].id,
          label: people[0]
            ? `${people[0].firstName} ${people[0].lastName}`
            : shipyards[0].name,
          kind: people[0] ? "person" : "shipyard",
        },
        warnings: ["Nie znaleziono produktu w zsynchronizowanym katalogu."],
        questions: ["Jaki produkt dodać do zamówienia?"],
      };
    }

    const items = matchedProducts.map((product) => this.itemFromMessage(product, normalized));
    const deliveryDate = this.deliveryDate(normalized);
    const requestId = randomUUID();
    return {
      ...base,
      status: "needs_data",
      recipient: {
        id: people[0]?.id ?? shipyards[0].id,
        label: people[0]
          ? `${people[0].firstName} ${people[0].lastName}`
          : shipyards[0].name,
        kind: people[0] ? "person" : "shipyard",
      },
      deliveryDate,
      items,
      needsData: [
        {
          id: requestId,
          tool: "get_current_stock",
          arguments: { productIds: items.map((item) => item.productId) },
        },
      ],
    };
  }

  private itemFromMessage(product: ProductRecord, normalizedMessage: string): OrderItemProposal {
    const aliases = labelsForProduct(product).map(normalize).sort((a, b) => b.length - a.length);
    const alias = aliases.find((candidate) => normalizedMessage.includes(candidate));
    const prefix = alias ? normalizedMessage.slice(0, normalizedMessage.indexOf(alias)) : "";
    const quantity = Number(prefix.match(/(\d+(?:[.,]\d+)?)\s*$/)?.[1]?.replace(",", ".") ?? 1);
    return {
      productId: product.id,
      label: productLabel(product),
      quantity,
      unit: product.unit,
    };
  }

  private deliveryDate(normalizedMessage: string): string | undefined {
    if (!normalizedMessage.includes("jutro")) return undefined;
    const date = new Date(this.now());
    date.setUTCDate(date.getUTCDate() + 1);
    return date.toISOString().slice(0, 10);
  }

  private base(sessionId: string): AgentResponse {
    return {
      schemaVersion: PROTOCOL_VERSION,
      sessionId,
      status: "error",
      intent: "ORDER",
      items: [],
      warnings: [],
      questions: [],
      candidates: [],
      needsData: [],
    };
  }

  private error(sessionId: string, code: string, message: string): AgentResponse {
    return { ...this.base(sessionId), error: { code, message } };
  }
}
