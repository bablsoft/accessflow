/**
 * Reconstructs query-result rows into document objects for the JSON result view. Each row is zipped
 * with the column names into an object, preserving nested values. Primarily used to render MongoDB
 * results as documents, but works for any engine's result page.
 */
export interface ResultColumnLike {
  name: string;
}

export function rowsToDocuments(
  columns: ResultColumnLike[],
  rows: unknown[][],
): Record<string, unknown>[] {
  return rows.map((row) => {
    const doc: Record<string, unknown> = {};
    columns.forEach((col, idx) => {
      doc[col.name] = row[idx] ?? null;
    });
    return doc;
  });
}

/** Pretty-printed JSON for the document view. */
export function documentsToJson(
  columns: ResultColumnLike[],
  rows: unknown[][],
): string {
  return JSON.stringify(rowsToDocuments(columns, rows), null, 2);
}
