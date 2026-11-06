import { performance } from "node:perf_hooks";

const enabled = process.env.AGENT_TIMINGS === "1";

export function recordTiming(stage: string, started: number, calls?: number): void {
  if (!enabled) return;
  console.info(JSON.stringify({ event: "agent_timing", stage, ms: Math.round((performance.now() - started) * 100) / 100, calls }));
}

export async function measureStage<T>(stage: string, run: () => Promise<T>): Promise<T> {
  if (!enabled) return run();
  const started = performance.now();
  try { return await run(); } finally { recordTiming(stage, started); }
}
