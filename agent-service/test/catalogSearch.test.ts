import assert from "node:assert/strict";
import test from "node:test";
import { CatalogStore, type ProductRecord } from "../src/catalog.js";

test("catalog search preserves aliases, limits and visibility across full and delta revisions", () => {
  const catalog = new CatalogStore();
  const snapshot = {
    revision: 1,
    products: [
      { id: "hidden", name: "Rękawice", unit: "para", hidden: true },
      ...Array.from({ length: 21 }, (_, n) =>
        ({ id: String(n), name: "Rękawice", variant: "9", unit: "para", aliases: ["Wampirki"] })),
    ],
    people: [{ id: "p", firstName: "Jan", lastName: "Nowak", aliases: ["Jasio"] }],
    shipyards: [{ id: "s", name: "Elektro", leaders: ["p"] }],
    taskPlaces: [],
  };
  catalog.fullSync(snapshot);
  const first = catalog.search("search_products", "rekawice 9") as ProductRecord[];
  assert.equal(first.length, 20);
  assert.equal(first[0].id, "0");
  first[0].aliases?.push("only in result");
  assert.deepEqual(catalog.search("search_products", "only in result"), []);
  assert.equal(catalog.search("search_products", "wampirki").length, 20);
  assert.equal(catalog.search("search_shipyards", "jasio").length, 1);
  catalog.deltaSync({ baseRevision: 1, revision: 2, deletes: { products: ["0"], people: ["p"] } });
  assert.equal((catalog.search("search_products", "rekawice 9") as ProductRecord[])[0].id, "1");
  assert.deepEqual(catalog.search("search_shipyards", "jasio"), []);
  catalog.fullSync({ ...snapshot, revision: 3, products: [] });
  assert.deepEqual(catalog.search("search_products", "wampirki"), []);
});
