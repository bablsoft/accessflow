/**
 * Connector prices and budgets are stored as integer micro-units (#971): 1 unit = 1 000 000
 * micros. The unit is deliberately unlabelled — never render a currency sign.
 */
const MICROS_PER_UNIT = 1_000_000;

/** Micros → display units (up to six decimals); null/undefined pass through as null. */
export function fromMicros(micros: number | null | undefined): number | null {
  if (micros === null || micros === undefined) return null;
  return micros / MICROS_PER_UNIT;
}

/** Display units → integer micros, rounding half up at the sixth decimal; null passes through. */
export function toMicros(units: number | null | undefined): number | null {
  if (units === null || units === undefined) return null;
  // Trim float noise before rounding: 0.0000005 * 1e6 is 0.49999999999999994, not 0.5.
  return Math.round(Number((units * MICROS_PER_UNIT).toPrecision(15)));
}

/** Locale-formatted units for charts and badges (no currency); null renders as an em dash. */
export function formatMicros(micros: number | null | undefined, locale?: string): string {
  const units = fromMicros(micros);
  if (units === null) return '—';
  return new Intl.NumberFormat(locale, { maximumFractionDigits: 6 }).format(units);
}
