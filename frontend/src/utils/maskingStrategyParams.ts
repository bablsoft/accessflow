import type { MaskingDatePrecision, MaskingStrategy } from '@/types/api';
import { parseBoundaries } from '@/utils/maskingPreview';

/** Limits mirrored from the backend `MaskingStrategyParamsValidator`. */
export const MAX_VISIBLE_LENGTH = 256;
export const MAX_REPLACEMENT_LENGTH = 256;
export const MAX_PATTERN_LENGTH = 512;
export const MAX_BOUNDARIES = 50;

export type BucketMode = 'SIZE' | 'BOUNDARIES';

/** The per-strategy parameter fields a masking-policy form carries alongside its own fields. */
export interface StrategyParamFormValues {
  visible_suffix?: number | null;
  visible_prefix?: number | null;
  replacement?: string;
  pattern?: string;
  bucket_mode?: BucketMode;
  bucket_size?: number | null;
  boundaries?: string;
  precision?: MaskingDatePrecision;
}

/**
 * Builds the wire `strategy_params` for a strategy from the form. Only the keys that strategy
 * accepts are sent — the backend rejects any other key. Returns undefined when there are none.
 */
export function strategyParamsFromForm(
  strategy: MaskingStrategy | undefined,
  values: StrategyParamFormValues,
): Record<string, string> | undefined {
  switch (strategy) {
    case 'PARTIAL':
      return values.visible_suffix != null
        ? { visible_suffix: String(values.visible_suffix) }
        : undefined;
    case 'KEEP_FIRST':
      return values.visible_prefix != null
        ? { visible_prefix: String(values.visible_prefix) }
        : undefined;
    case 'CONSTANT':
      return { replacement: values.replacement ?? '' };
    case 'REGEX_REPLACE':
      return { pattern: values.pattern ?? '', replacement: values.replacement ?? '' };
    case 'NUMERIC_BUCKET':
      return values.bucket_mode === 'BOUNDARIES'
        ? { boundaries: (values.boundaries ?? '').trim() }
        : { bucket_size: values.bucket_size != null ? String(values.bucket_size) : '' };
    case 'DATE_GENERALIZE':
      return { precision: values.precision ?? 'YEAR' };
    default:
      return undefined;
  }
}

/** Inverse of {@link strategyParamsFromForm}, for editing an existing policy. */
export function formValuesFromStrategyParams(
  params: Record<string, string> | undefined | null,
): StrategyParamFormValues {
  const p = params ?? {};
  const num = (raw: string | undefined) =>
    raw != null && raw.trim() !== '' && !Number.isNaN(Number(raw)) ? Number(raw) : undefined;
  return {
    visible_suffix: num(p.visible_suffix),
    visible_prefix: num(p.visible_prefix),
    replacement: p.replacement,
    pattern: p.pattern,
    bucket_mode: p.boundaries != null && p.boundaries !== '' ? 'BOUNDARIES' : 'SIZE',
    bucket_size: num(p.bucket_size),
    boundaries: p.boundaries,
    precision: (p.precision as MaskingDatePrecision | undefined) ?? 'YEAR',
  };
}

/** Mirrors the backend boundaries rule: 1–50 strictly ascending comma-separated numbers. */
export function isValidBoundaries(raw: string | undefined): boolean {
  const parsed = parseBoundaries(raw);
  return parsed != null && parsed.length <= MAX_BOUNDARIES;
}

/**
 * Mirrors the backend's empty-match rejection for patterns the browser can compile; anything it
 * cannot compile is left for the server's Java engine to judge.
 */
export function matchesEmptyString(pattern: string): boolean {
  try {
    return new RegExp(pattern).test('');
  } catch {
    return false;
  }
}
