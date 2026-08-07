const referenceNow = (): number => Date.now();

export function timeAgo(iso: string | number | Date): string {
  const d = new Date(iso);
  const diff = (referenceNow() - d.getTime()) / 1000;
  if (diff < 60) return 'just now';
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)}d ago`;
  return d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' });
}

/**
 * Milliseconds elapsed since `iso`, or `NaN` when it is absent or unparseable. Callers decide what
 * an unusable timestamp means — this never guesses.
 */
export function msSince(iso: string | null | undefined): number {
  if (!iso) return Number.NaN;
  const parsed = Date.parse(iso);
  return Number.isNaN(parsed) ? Number.NaN : referenceNow() - parsed;
}

export function fmtDate(iso: string | number | Date): string {
  const d = new Date(iso);
  return d.toLocaleString('en-US', {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  });
}

export function fmtNum(n: number | null | undefined): string {
  if (n == null) return '—';
  return n.toLocaleString('en-US');
}
