import type { CatalogSnapshot } from "./catalog.js";

export const SEARCH_TOOLS = ["search_people", "search_products", "search_shipyards", "search_task_places"] as const;
export type SearchTool = typeof SEARCH_TOOLS[number];
const normalize = (s: string): string => s.normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLocaleLowerCase("pl-PL");

export function searchCatalog(catalog: CatalogSnapshot, tool: SearchTool, query: string): unknown[] {
  if (typeof query !== "string" || !query.trim() || query.length > 120) throw new Error("Nieprawidłowe zapytanie katalogu.");
  const q = normalize(query.trim());
  if (tool === "search_people") return catalog.people.filter(p =>
    [p.firstName, p.lastName, `${p.firstName} ${p.lastName}`, ...(p.aliases ?? []), ...(p.positions ?? [])]
      .some(s => normalize(s).includes(q))).slice(0, 20).map(p => ({ ...p }));
  if (tool === "search_products") return catalog.products.filter(p => !p.hidden &&
    [p.name, [p.name, p.variant].filter(Boolean).join(" "), p.variant ?? "", ...(p.aliases ?? []), ...(p.tags ?? [])]
      .some(s => normalize(s).includes(q))).slice(0, 20).map(p => ({ ...p }));
  if (tool === "search_shipyards") return catalog.shipyards.filter(p =>
    [p.name, ...(p.aliases ?? []), ...(p.tags ?? []), ...(p.leaders ?? [])].some(s => normalize(s).includes(q))).slice(0, 20).map(p => ({ ...p }));
  if (tool === "search_task_places") return catalog.taskPlaces.filter(p =>
    [p.name, ...(p.aliases ?? [])].some(s => normalize(s).includes(q))).slice(0, 20).map(p => ({ ...p }));
  throw new Error("Nieznane narzędzie katalogu.");
}
