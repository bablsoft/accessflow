/**
 * Client-side mirror of the backend `ResultExportWatermark` templates (AF-626), used to render a
 * live preview of the watermark stamped onto governed result exports. The strings must match the
 * backend byte-for-byte — the watermark is baked into the signed export, so the preview's job is
 * to show admins exactly what exporters will see. Timestamps render at second precision in UTC,
 * matching `Instant.truncatedTo(SECONDS)` on the backend.
 */

function utcSeconds(date: Date): string {
  // 2026-08-18T09:30:00Z — ISO-8601, second precision, matching Instant.toString().
  return `${date.toISOString().slice(0, 19)}Z`;
}

/** `AccessFlow export | <email> | <utc-iso-8601> | query <queryRequestId>` */
export function watermarkHeader(
  exporterEmail: string,
  timestamp: Date,
  queryRequestId: string,
): string {
  return `AccessFlow export | ${exporterEmail} | ${utcSeconds(timestamp)} | query ${queryRequestId}`;
}

/** `AccessFlow export end | <n> rows` plus ` | capped at <cap>` when capped. */
export function watermarkFooter(rowCount: number, rowCap?: number | null): string {
  const footer = `AccessFlow export end | ${rowCount} rows`;
  return rowCap == null ? footer : `${footer} | capped at ${rowCap}`;
}
