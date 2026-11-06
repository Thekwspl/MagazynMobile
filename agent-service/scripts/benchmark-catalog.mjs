import assert from "node:assert/strict";
import { performance } from "node:perf_hooks";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { CatalogStore } from "../dist/src/catalog.js";

const baseline = process.argv[2];
if (!baseline) throw new Error("Usage: node scripts/benchmark-catalog.mjs <baseline agent-service/dist/src>");
const fromBaseline = name => import(pathToFileURL(resolve(baseline, name)).href);
const { CatalogStore: OldStore } = await fromBaseline("catalog.js");
const { searchCatalog } = await fromBaseline("catalogTools.js");

const snapshot = {
  revision: 1,
  products: Array.from({ length: 1200 }, (_, i) => ({
    id: `p${i}`, name: `Rękawice ${i}`, variant: String(i % 12),
    unit: "para", aliases: [`alias-${i}`], tags: ["BHP"], hidden: i % 13 === 0,
  })),
  people: Array.from({ length: 400 }, (_, i) => ({ id: `e${i}`, firstName: "Jan", lastName: `Pracownik-${i}` })),
  shipyards: Array.from({ length: 6 }, (_, i) => ({ id: `s${i}`, name: `Stocznia ${i}`, leaders: [`e${i}`] })),
  taskPlaces: [],
};
const product = n => ["search_products", `alias-${1101 + n}`];
const scenarios = {
  A: [product(0), product(1)],
  B: [product(0), product(1), product(2), product(3), ["search_products", "rękawice"]],
  C: Array.from({ length: 40 }, (_, i) => product(i)),
  D: [product(0), ["search_people", "Pracownik-399"], ["search_shipyards", "Pracownik-3"]],
  E: [["search_products", "rękawice"], ["search_people", "Jan"]],
};
const baselineLookup = (store, tool, query) => searchCatalog(store.read(), tool, query);
const nextLookup = (store, tool, query) => store.search(tool, query);
const batch = (store, lookup, searches) => searches.map(([tool, query]) => lookup(store, tool, query));
const make = Store => { const store = new Store(); store.fullSync(snapshot); return store; };

function measure(Store, lookup, searches, cold) {
  const warm = make(Store);
  for (let i = 0; i < 3; i++) batch(warm, lookup, searches);
  const samples = Array.from({ length: 30 }, () => {
    const store = cold ? make(Store) : warm;
    const start = performance.now();
    batch(store, lookup, searches);
    return performance.now() - start;
  }).sort((a, b) => a - b);
  const rounded = n => Number(n.toFixed(3));
  return {
    n: samples.length,
    meanMs: rounded(samples.reduce((a, b) => a + b, 0) / samples.length),
    medianMs: rounded((samples[14] + samples[15]) / 2),
    p95Ms: rounded(samples[Math.ceil(samples.length * 0.95) - 1]),
  };
}

for (const [name, searches] of Object.entries(scenarios)) {
  assert.deepEqual(batch(make(OldStore), baselineLookup, searches), batch(make(CatalogStore), nextLookup, searches));
  for (const cold of [true, false]) {
    console.log(JSON.stringify({
      name, catalog: cold ? "fresh_revision" : "warm_revision",
      searchCalls: searches.length, modelTurns: 0,
      before: measure(OldStore, baselineLookup, searches, cold),
      after: measure(CatalogStore, nextLookup, searches, cold),
    }));
  }
}
