import type { TFunction } from 'i18next';

/** Human-readable duration of a job run: milliseconds under a second, seconds under a minute. */
export function formatDurationMs(t: TFunction, ms: number | null | undefined): string {
  if (ms == null) return '—';
  if (ms < 1000) return t('admin.jobs.duration_ms', { value: Math.round(ms) });
  if (ms < 60_000) return t('admin.jobs.duration_s', { value: (ms / 1000).toFixed(1) });
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return t('admin.jobs.duration_m', { minutes, seconds });
}

const ISO_DURATION = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$/;

/** An ISO-8601 duration from the backend ("PT24H", "P1D", "PT30S") in words; the raw text when unparseable. */
export function formatIsoDuration(t: TFunction, iso: string | null | undefined): string {
  if (iso == null) return '—';
  const match = ISO_DURATION.exec(iso);
  if (match == null) return iso;
  const [, d, h, m, s] = match;
  const parts = [
    d != null ? t('admin.jobs.unit_d', { value: Number(d) }) : null,
    h != null ? t('admin.jobs.unit_h', { value: Number(h) }) : null,
    m != null ? t('admin.jobs.unit_m', { value: Number(m) }) : null,
    s != null ? t('admin.jobs.unit_s', { value: Number(s) }) : null,
  ].filter((p): p is string => p != null);
  return parts.length === 0 ? iso : parts.join(' ');
}
