/**
 * Time-to-live helpers for time-boxed access grants. Pure functions (no i18n) so callers can
 * format the wrapper text ("… left" / "Expired") with `t()` while reusing the compact value.
 */

/** Milliseconds until {@code expiresAt}; negative when already past; null when no expiry. */
export function remainingTtlMs(expiresAt: string | null | undefined, nowMs?: number): number | null {
  if (!expiresAt) {
    return null;
  }
  const expiry = Date.parse(expiresAt);
  if (Number.isNaN(expiry)) {
    return null;
  }
  return expiry - (nowMs ?? Date.now());
}

/**
 * Compact, locale-neutral duration label: "3d 4h", "5h 12m", "8m", or "<1m". Negative/zero
 * inputs collapse to "<1m". Shows at most the two largest non-zero units.
 */
export function formatDurationCompact(ms: number): string {
  const totalMinutes = Math.floor(ms / 60_000);
  if (totalMinutes < 1) {
    return '<1m';
  }
  const days = Math.floor(totalMinutes / 1440);
  const hours = Math.floor((totalMinutes % 1440) / 60);
  const minutes = totalMinutes % 60;
  const parts: string[] = [];
  if (days > 0) parts.push(`${days}d`);
  if (hours > 0) parts.push(`${hours}h`);
  if (minutes > 0) parts.push(`${minutes}m`);
  return parts.slice(0, 2).join(' ');
}
