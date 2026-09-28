import type { MaskingStrategy } from '@/types/api';

/**
 * Client-side mirror of the backend `ColumnMasker` strategies, used to render a live preview of how
 * a value will look once masked. Strategies reproduce the backend output and its fail-closed
 * fallbacks, with these approximations — the server is authoritative: HASH is shown as an
 * illustrative fixed SHA-256-shaped digest; REGEX_REPLACE runs on the browser's regex engine (which
 * differs from Java's in a few constructs, and has no backtracking budget, so it only evaluates
 * samples up to {@link MAX_REGEX_PREVIEW_LENGTH} characters); NUMERIC_BUCKET uses floating point
 * where the server uses exact decimals. NULLIFY previews as `null`.
 */
export const FULL_MASK = '***';
export const DEFAULT_VISIBLE_SUFFIX = 4;
export const DEFAULT_VISIBLE_PREFIX = 4;
/** Returned instead of a preview when the sample is too long to evaluate a regex safely. */
export const REGEX_PREVIEW_SKIPPED = '';
/**
 * The browser regex engine cannot be interrupted, so a catastrophic pattern would freeze the tab on
 * every keystroke. Short samples keep even exponential backtracking to a few million steps.
 */
export const MAX_REGEX_PREVIEW_LENGTH = 20;

// SHA-256 of the empty string — a real, fixed sample used only to illustrate the HASH output shape.
const ILLUSTRATIVE_HASH =
  'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

const DECIMAL = /^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/;
const DATE_PREFIX = /^(\d{4})-(\d{2})/;

export function maskingPreview(
  strategy: MaskingStrategy,
  raw: string,
  params?: Record<string, string>,
): string | null {
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
    case 'KEEP_FIRST':
      return keepFirst(raw, params);
    case 'CONSTANT':
      return params?.replacement ? params.replacement : FULL_MASK;
    case 'NULLIFY':
      return null;
    case 'REGEX_REPLACE':
      return regexReplace(raw, params);
    case 'NUMERIC_BUCKET':
      return numericBucket(raw, params);
    case 'DATE_GENERALIZE':
      return dateGeneralize(raw, params);
    default:
      return FULL_MASK;
  }
}

function intParam(raw: string | undefined, fallback: number): number {
  if (raw == null || raw.trim() === '') {
    return fallback;
  }
  const value = Number.parseInt(raw.trim(), 10);
  return Number.isNaN(value) || value < 0 ? fallback : value;
}

function partial(raw: string, params?: Record<string, string>): string {
  const visible = intParam(params?.visible_suffix, DEFAULT_VISIBLE_SUFFIX);
  if (raw.length <= visible) {
    return '*'.repeat(raw.length);
  }
  return '*'.repeat(raw.length - visible) + raw.slice(raw.length - visible);
}

function keepFirst(raw: string, params?: Record<string, string>): string {
  const visible = intParam(params?.visible_prefix, DEFAULT_VISIBLE_PREFIX);
  if (raw.length <= visible) {
    return '*'.repeat(raw.length);
  }
  return raw.slice(0, visible) + '*'.repeat(raw.length - visible);
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

/**
 * Translates a Java `Matcher` replacement template into the browser's syntax: `\x` escapes become
 * literals (`\$` → `$$`), `${name}` becomes `$<name>`. Returns null for a template Java would reject.
 */
export function javaReplacementToJs(replacement: string): string | null {
  let out = '';
  let i = 0;
  while (i < replacement.length) {
    const c = replacement.charAt(i);
    if (c === '\\') {
      if (i + 1 >= replacement.length) {
        return null;
      }
      const next = replacement.charAt(i + 1);
      out += next === '$' ? '$$' : next;
      i += 2;
    } else if (c === '$') {
      const next = replacement.charAt(i + 1);
      if (next === '{') {
        const close = replacement.indexOf('}', i + 2);
        if (close < 0) {
          return null;
        }
        out += `$<${replacement.slice(i + 2, close)}>`;
        i = close + 1;
      } else if (/\d/.test(next)) {
        out += `$${next}`;
        i += 2;
      } else {
        return null;
      }
    } else {
      out += c;
      i += 1;
    }
  }
  return out;
}

function regexReplace(raw: string, params?: Record<string, string>): string {
  const pattern = params?.pattern;
  const replacement = params?.replacement;
  if (!pattern || replacement == null) {
    return FULL_MASK;
  }
  if (raw.length > MAX_REGEX_PREVIEW_LENGTH) {
    return REGEX_PREVIEW_SKIPPED;
  }
  const jsReplacement = javaReplacementToJs(replacement);
  if (jsReplacement == null) {
    return FULL_MASK;
  }
  let regex: RegExp;
  try {
    regex = new RegExp(pattern, 'g');
  } catch {
    return FULL_MASK;
  }
  if (![...raw.matchAll(regex)].some((m) => m[0].length > 0)) {
    // Mirrors the backend: a value the pattern does not match — or matches only with empty strings —
    // is masked, never passed through.
    return FULL_MASK;
  }
  return raw.replace(regex, jsReplacement);
}

function parseDecimal(raw: string | undefined): number | null {
  if (raw == null || !DECIMAL.test(raw.trim())) {
    return null;
  }
  const value = Number(raw.trim());
  return Number.isFinite(value) ? value : null;
}

/** Parses a strictly ascending comma-separated list; null when malformed. */
export function parseBoundaries(raw: string | undefined): number[] | null {
  if (raw == null || raw.trim() === '') {
    return null;
  }
  const result: number[] = [];
  for (const part of raw.split(',')) {
    const value = parseDecimal(part);
    const last = result[result.length - 1];
    if (value == null || (last != null && value <= last)) {
      return null;
    }
    result.push(value);
  }
  return result;
}

function plain(value: number): string {
  return String(Number(value.toPrecision(12)));
}

function numericBucket(raw: string, params?: Record<string, string>): string {
  const value = parseDecimal(raw);
  if (value == null) {
    return FULL_MASK;
  }
  const size = parseDecimal(params?.bucket_size);
  if (size != null && size > 0) {
    return plain(Math.floor(value / size) * size);
  }
  const boundaries = parseBoundaries(params?.boundaries);
  if (boundaries == null) {
    return FULL_MASK;
  }
  const first = boundaries[0] as number;
  if (value < first) {
    return `<${plain(first)}`;
  }
  for (let i = 0; i < boundaries.length - 1; i += 1) {
    const upper = boundaries[i + 1] as number;
    if (value < upper) {
      return `[${plain(boundaries[i] as number)}, ${plain(upper)})`;
    }
  }
  return `>=${plain(boundaries[boundaries.length - 1] as number)}`;
}

function dateGeneralize(raw: string, params?: Record<string, string>): string {
  const match = DATE_PREFIX.exec(raw.trim());
  const precision = params?.precision?.trim().toUpperCase();
  if (!match || !precision) {
    return FULL_MASK;
  }
  const year = match[1] as string;
  const monthText = match[2] as string;
  const month = Number(monthText);
  if (month < 1 || month > 12) {
    return FULL_MASK;
  }
  switch (precision) {
    case 'YEAR':
      return year;
    case 'QUARTER':
      return `${year}-Q${Math.floor((month - 1) / 3) + 1}`;
    case 'MONTH':
      return `${year}-${monthText}`;
    default:
      return FULL_MASK;
  }
}
