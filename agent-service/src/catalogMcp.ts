import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";
import { SEARCH_TOOLS } from "./catalogTools.js";

// The catalog lives in the parent agent-service. This subprocess has no write tools.
const url = process.env.WAREHOUSE_CATALOG_URL;
const token = process.env.WAREHOUSE_CATALOG_TOKEN;
if (!url || !token) throw new Error("Brak lokalnego połączenia z katalogiem.");
const server = new McpServer({ name: "warehouse-catalog", version: "0.1.0" });
for (const name of SEARCH_TOOLS) {
  server.registerTool(name, {
    description: `Read-only, bounded search of warehouse ${name.slice(7).replaceAll("_", " ")}. Search by a short name, surname, alias or variant. Returns stable IDs, never live stock.`,
    inputSchema: { query: z.string().min(1).max(120) },
    annotations: { readOnlyHint: true, destructiveHint: false, openWorldHint: false },
  }, async ({ query }) => {
    const response = await fetch(url, {
      method: "POST", headers: { "content-type": "application/json", authorization: `Bearer ${token}` },
      body: JSON.stringify({ tool: name, query }), signal: AbortSignal.timeout(10000),
    });
    if (!response.ok) throw new Error(`Katalog niedostępny: HTTP ${response.status}`);
    return { content: [{ type: "text" as const, text: JSON.stringify(await response.json()) }] };
  });
}
await server.connect(new StdioServerTransport());
