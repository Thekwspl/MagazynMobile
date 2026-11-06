import type { CatalogSnapshot } from "./catalog.js";

export const SEARCH_TOOLS = ["search_people", "search_products", "search_shipyards", "search_task_places"] as const;
export type SearchTool = typeof SEARCH_TOOLS[number];
const normalize = (s: string): string => s.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase("pl-PL");

type SearchRecord = CatalogSnapshot["people" | "products" | "shipyards" | "taskPlaces"][number];
type Entry = { record: SearchRecord; labels: string[] };
const indexes = new WeakMap<CatalogSnapshot, { revision: number; tools: Map<SearchTool, Entry[]> }>();

function buildIndex(catalog: CatalogSnapshot, tool: SearchTool): Entry[] {
  const entry = (record: SearchRecord, labels: string[]): Entry => ({ record, labels: labels.map(normalize) });
  switch (tool) {
    case "search_people": return catalog.people.map(p => entry(p,
      [p.firstName, p.lastName, `${p.firstName} ${p.lastName}`, ...(p.aliases ?? []), ...(p.positions ?? [])]));
    case "search_products": return catalog.products.filter(p => !p.hidden).map(p => entry(p,
      [p.name, [p.name, p.variant].filter(Boolean).join(" "), p.variant ?? "", ...(p.aliases ?? []), ...(p.tags ?? [])]));
    case "search_shipyards": {
      const leaderLabels = new Map(catalog.people.map(p => [p.id, [`${p.firstName} ${p.lastName}`, p.lastName, ...(p.aliases ?? [])]]));
      return catalog.shipyards.map(p => entry(p,
        [p.name, ...(p.aliases ?? []), ...(p.tags ?? []), ...(p.leaders ?? []),
          ...(p.leaders ?? []).flatMap(id => leaderLabels.get(id) ?? [])]));
    }
    case "search_task_places": return catalog.taskPlaces.map(p => entry(p, [p.name, ...(p.aliases ?? [])]));
  }
  throw new Error("Nieznane narzędzie katalogu.");
}

export function searchCatalog(catalog: CatalogSnapshot, tool: SearchTool, query: string): unknown[] {
  if (typeof query !== "string" || !query.trim() || query.length > 120) throw new Error("Nieprawidłowe zapytanie katalogu.");
  let index = indexes.get(catalog);
  if (!index || index.revision !== catalog.revision) {
    index = { revision: catalog.revision, tools: new Map() };
    indexes.set(catalog, index);
  }
  let entries = index.tools.get(tool);
  if (!entries) {
    entries = buildIndex(catalog, tool);
    index.tools.set(tool, entries);
  }
  const q = normalize(query.trim());
  const matches: SearchRecord[] = [];
  for (const { record, labels } of entries) {
    if (labels.some(label => label.includes(q))) matches.push(record);
    if (matches.length === 20) break;
  }
  return structuredClone(matches);
}
