import assert from "node:assert/strict";
import test from "node:test";
import { CatalogStore, type CatalogSnapshot } from "../src/catalog.js";
import { PROTOCOL_VERSION } from "../src/contracts.js";
import { WarehouseAgent } from "../src/warehouseAgent.js";

const fixture = (duplicateKowalski = false): CatalogSnapshot => ({
  revision: 1,
  people: [
    { id: "person-jan", firstName: "Jan", lastName: "Kowalski" },
    { id: "person-anna", firstName: "Anna", lastName: "Nowak" },
    ...(duplicateKowalski
      ? [{ id: "person-adam", firstName: "Adam", lastName: "Kowalski" }]
      : []),
  ],
  products: [
    {
      id: "product-gloves-xl",
      name: "Rękawice robocze",
      variant: "XL",
      unit: "para",
      aliases: ["rękawice XL"],
    },
    {
      id: "product-glasses",
      name: "Okulary ochronne",
      unit: "szt.",
      aliases: ["okulary"],
    },
  ],
  shipyards: [{ id: "shipyard-ulstein", name: "Ulstein - Elektro" }],
  taskPlaces: [{ id: "task-place-hall-a", name: "Hala A" }],
});

const makeAgent = (snapshot = fixture()) => {
  const store = new CatalogStore();
  store.fullSync(snapshot);
  return {
    store,
    agent: new WarehouseAgent(
      () => store.read(),
      () => new Date("2026-09-24T10:00:00Z"),
    ),
  };
};

test("builds a versioned needs_data request for a recognized order", () => {
  const { agent } = makeAgent();
  const response = agent.start("Kowalski jutro 2 rękawice XL i okulary");

  assert.equal(response.schemaVersion, PROTOCOL_VERSION);
  assert.equal(response.status, "needs_data");
  assert.equal(response.recipient?.id, "person-jan");
  assert.equal(response.deliveryDate, "2026-09-25");
  assert.deepEqual(
    response.items.map(({ productId, quantity }) => ({ productId, quantity })),
    [
      { productId: "product-gloves-xl", quantity: 2 },
      { productId: "product-glasses", quantity: 1 },
    ],
  );
  assert.deepEqual(response.needsData[0].arguments.productIds, [
    "product-gloves-xl",
    "product-glasses",
  ]);
});

test("asks the user to choose when a person is ambiguous", () => {
  const { agent } = makeAgent(fixture(true));
  const response = agent.start("Kowalski jutro okulary");

  assert.equal(response.status, "needs_user_choice");
  assert.deepEqual(response.candidates.map((candidate) => candidate.id), [
    "person-jan",
    "person-adam",
  ]);
});

test("reports a product missing from the synchronized catalog", () => {
  const { agent } = makeAgent();
  const response = agent.start("Jan Kowalski jutro młotek");

  assert.equal(response.status, "needs_user_choice");
  assert.match(response.warnings[0], /Nie znaleziono produktu/);
  assert.ok(response.questions.length > 0);
});

test("resumes after read-only stock data and returns a proposal", () => {
  const { agent } = makeAgent();
  const initial = agent.start("Jan Kowalski jutro 2 rękawice XL");
  const response = agent.resumeWithData(initial.sessionId, [
    {
      requestId: initial.needsData[0].id,
      tool: "get_current_stock",
      data: { stocks: [{ productId: "product-gloves-xl", available: 1 }] },
    },
  ]);

  assert.equal(response.status, "proposal");
  assert.equal(response.items[0].available, 1);
  assert.match(response.warnings[0], /Niewystarczający stan/);
});

test("rejects a write tool result", () => {
  const { agent } = makeAgent();
  const initial = agent.start("Jan Kowalski jutro okulary");
  const response = agent.resumeWithData(initial.sessionId, [
    {
      requestId: initial.needsData[0].id,
      tool: "issue_items",
      data: { stocks: [] },
    },
  ]);

  assert.equal(response.status, "error");
  assert.equal(response.error?.code, "TOOL_NOT_ALLOWED");
});

test("applies catalog deltas only to the expected revision", () => {
  const { store } = makeAgent();
  store.deltaSync({
    baseRevision: 1,
    revision: 2,
    deletes: { people: ["person-anna"] },
    upserts: { shipyards: [{ id: "shipyard-verft", name: "Vard Verft" }] },
  });

  assert.equal(store.read().revision, 2);
  assert.deepEqual(store.read().people.map((person) => person.id), ["person-jan"]);
  assert.throws(() =>
    store.deltaSync({ baseRevision: 1, revision: 3 }),
  );
});
