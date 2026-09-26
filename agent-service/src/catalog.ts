export interface PersonRecord {
  id: string;
  firstName: string;
  lastName: string;
  aliases?: string[];
  positions?: string[];
  doNotHire?: boolean;
}

export interface ProductRecord {
  id: string;
  name: string;
  variant?: string;
  unit: string;
  aliases?: string[];
  tags?: string[];
  hidden?: boolean;
}

export interface ShipyardRecord {
  id: string;
  name: string;
  aliases?: string[];
  tags?: string[];
  leaders?: string[];
}

export interface TaskPlaceRecord {
  id: string;
  name: string;
  aliases?: string[];
}

export interface CatalogSnapshot {
  revision: number;
  people: PersonRecord[];
  products: ProductRecord[];
  shipyards: ShipyardRecord[];
  taskPlaces: TaskPlaceRecord[];
}

export type CatalogKind = "people" | "products" | "shipyards" | "taskPlaces";

export interface CatalogDelta {
  baseRevision: number;
  revision: number;
  upserts?: Partial<Pick<CatalogSnapshot, CatalogKind>>;
  deletes?: Partial<Record<CatalogKind, string[]>>;
}

const emptySnapshot = (): CatalogSnapshot => ({
  revision: 0,
  people: [],
  products: [],
  shipyards: [],
  taskPlaces: [],
});

export class CatalogStore {
  private snapshot: CatalogSnapshot = emptySnapshot();

  read(): CatalogSnapshot {
    return structuredClone(this.snapshot);
  }

  fullSync(next: CatalogSnapshot): void {
    if (next.revision <= this.snapshot.revision) {
      throw new Error("Catalog revision must increase");
    }
    this.snapshot = structuredClone(next);
  }

  deltaSync(delta: CatalogDelta): void {
    if (delta.baseRevision !== this.snapshot.revision) {
      throw new Error("Catalog delta base revision does not match");
    }
    if (delta.revision <= delta.baseRevision) {
      throw new Error("Catalog revision must increase");
    }

    for (const kind of ["people", "products", "shipyards", "taskPlaces"] as const) {
      const deleted = new Set(delta.deletes?.[kind] ?? []);
      const records = this.snapshot[kind].filter((record) => !deleted.has(record.id));
      const byId = new Map(records.map((record) => [record.id, record]));
      for (const record of delta.upserts?.[kind] ?? []) {
        byId.set(record.id, structuredClone(record) as never);
      }
      (this.snapshot[kind] as Array<{ id: string }>) = [...byId.values()];
    }
    this.snapshot.revision = delta.revision;
  }
}
