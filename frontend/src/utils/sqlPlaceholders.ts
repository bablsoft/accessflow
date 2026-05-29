// `:identifier` placeholder support for query templates (AF-364).
// Negative lookbehind `(?<![:\w])` excludes:
//   - PostgreSQL `::` casts (`x::text`)
//   - colons inside identifiers (`schema:name` — not valid SQL, but defensive)
const PLACEHOLDER_RE = /(?<![:\w]):([a-zA-Z_][a-zA-Z0-9_]*)/g;

export function extractPlaceholders(sql: string): string[] {
  const seen = new Set<string>();
  for (const m of sql.matchAll(PLACEHOLDER_RE)) {
    const name = m[1];
    if (name) seen.add(name);
  }
  return [...seen];
}

export function substitutePlaceholders(
  sql: string,
  values: Record<string, string>,
): string {
  return sql.replace(PLACEHOLDER_RE, (full: string, name: string) =>
    Object.prototype.hasOwnProperty.call(values, name) ? values[name] ?? full : full,
  );
}
