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
  recognitionRules?: RecognitionRules;
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
    next = snapshotSchema.parse(next);
    if (next.revision <= this.snapshot.revision) {
      throw new Error("Catalog revision must increase");
    }
    this.snapshot = structuredClone(next);
  }

  deltaSync(delta: CatalogDelta): void {
    // Learned rules use full replacement, never additive deltas that could retain deleted rules.
    delta = deltaSchema.parse(delta);
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
import { z } from "zod";

export interface RecognitionRule {
  id: string; type: "PRODUCT" | "PERSON" | "POSITION" | "PATTERN";
  triggerKey: string; sourceLabel: string; learnedName: string;
  learnedVariant: string | null; learnedUnit: string; resultExtra: string;
  targetIds: string[];
}
export interface RecognitionRules { instructions: string; learned: RecognitionRule[] }

const text = z.string().max(2000);
const id = z.string().min(1).max(128);
const labels = z.array(text).max(100);
const recordSchemas = {
  people: z.object({ id, firstName: text, lastName: text, aliases: labels.optional(), positions: labels.optional(), doNotHire: z.boolean().optional() }).strict(),
  products: z.object({ id, name: text, variant: text.optional(), unit: text, aliases: labels.optional(), tags: labels.optional(), hidden: z.boolean().optional() }).strict(),
  shipyards: z.object({ id, name: text, aliases: labels.optional(), tags: labels.optional(), leaders: z.array(id).max(100).optional() }).strict(),
  taskPlaces: z.object({ id, name: text, aliases: labels.optional() }).strict(),
};
const recognitionRulesSchema = z.object({ instructions: z.string().max(10_000), learned: z.array(z.object({
  id, type: z.enum(["PRODUCT", "PERSON", "POSITION", "PATTERN"]), triggerKey: text, sourceLabel: text,
  learnedName: text, learnedVariant: text.nullable(), learnedUnit: text, resultExtra: text, targetIds: z.array(id).max(100),
}).strict()).max(5000) }).strict();
const lists = {
  people: z.array(recordSchemas.people).max(20_000), products: z.array(recordSchemas.products).max(20_000),
  shipyards: z.array(recordSchemas.shipyards).max(5000), taskPlaces: z.array(recordSchemas.taskPlaces).max(5000),
};
const snapshotSchema = z.object({ revision: z.number().int().positive(), ...lists, recognitionRules: recognitionRulesSchema.optional() }).strict();
const deltaSchema = z.object({ baseRevision: z.number().int().nonnegative(), revision: z.number().int().positive(),
  upserts: z.object(lists).partial().strict().optional(),
  deletes: z.object({ people: z.array(id), products: z.array(id), shipyards: z.array(id), taskPlaces: z.array(id) }).partial().strict().optional(),
}).strict();
