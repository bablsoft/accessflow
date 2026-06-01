import type { MaskingStrategy } from '@/types/api';

/**
 * Client-side mirror of the backend `ColumnMasker` strategies, used to render a live preview of how
 * a value will look once masked. FULL / PARTIAL / EMAIL / FORMAT_PRESERVING reproduce the backend
 * output exactly. HASH is shown as an illustrative fixed SHA-256-shaped digest (the real digest is
 * computed server-side at result-read time) so the preview communicates the rendered shape without
 * recomputing a cryptographic hash in the browser.
 */
export const FULL_MASK = '***';
export const DEFAULT_VISIBLE_SUFFIX = 4;

// SHA-256 of the empty string — a real, fixed sample used only to illustrate the HASH output shape.
const ILLUSTRATIVE_HASH =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

export function maskingPreview(
  strategy: MaskingStrategy,
  raw: string,
  params?: Record<string, string>,
): string {
  if (raw === '') {
    return '';
  }
  switch (strategy) {
    case 'FULL':
      return FULL_MASK;
    case 'PARTIAL':
      return partial(raw, params);
    case 'HASH':
      return ILLUSTRATIVE_HASH;
    case 'EMAIL':
      return email(raw);
    case 'FORMAT_PRESERVING':
      return formatPreserving(raw);
    default:
      return raw;
  }
}

function visibleSuffix(params?: Record<string, string>): number {
  const raw = params?.visible_suffix;
  if (raw == null || raw.trim() === '') {
    return DEFAULT_VISIBLE_SUFFIX;
  }
  const value = Number.parseInt(raw.trim(), 10);
  return Number.isNaN(value) || value < 0 ? DEFAULT_VISIBLE_SUFFIX : value;
}

function partial(raw: string, params?: Record<string, string>): string {
  const visible = visibleSuffix(params);
  if (raw.length <= visible) {
    return '*'.repeat(raw.length);
  }
  return '*'.repeat(raw.length - visible) + raw.slice(raw.length - visible);
}

function email(raw: string): string {
  const at = raw.indexOf('@');
  if (at <= 0 || at === raw.length - 1) {
    return FULL_MASK;
  }
  return `${raw[0]}***@${raw.slice(at + 1)}`;
}

function formatPreserving(raw: string): string {
  let out = '';
  for (const ch of raw) {
    if (/\p{Nd}/u.test(ch)) {
      out += '*';
    } else if (/\p{L}/u.test(ch)) {
      out += 'x';
    } else {
      out += ch;
    }
  }
  return out;
}
