import { describe, expect, it } from 'vitest';
import { formatMicros, fromMicros, toMicros } from './micros';

describe('micros', () => {
  it('passes null and undefined through', () => {
    expect(fromMicros(null)).toBeNull();
    expect(fromMicros(undefined)).toBeNull();
    expect(toMicros(null)).toBeNull();
    expect(toMicros(undefined)).toBeNull();
    expect(formatMicros(null)).toBe('—');
  });

  it('converts micros to units', () => {
    expect(fromMicros(0)).toBe(0);
    expect(fromMicros(500_000)).toBe(0.5);
    expect(fromMicros(1)).toBe(0.000001);
    expect(fromMicros(12_345_678)).toBe(12.345678);
  });

  it('converts units to integer micros', () => {
    expect(toMicros(0)).toBe(0);
    expect(toMicros(0.5)).toBe(500_000);
    expect(toMicros(12.345678)).toBe(12_345_678);
    expect(toMicros(0.1 + 0.2)).toBe(300_000);
  });

  it('rounds half up at the sixth decimal', () => {
    expect(toMicros(0.0000005)).toBe(1);
    expect(toMicros(0.0000004)).toBe(0);
    expect(toMicros(1.2345675)).toBe(1_234_568);
  });

  it('formats units without a currency, up to six decimals', () => {
    expect(formatMicros(500_000, 'en')).toBe('0.5');
    expect(formatMicros(1_234_567_891, 'en')).toBe('1,234.567891');
    expect(formatMicros(0, 'en')).toBe('0');
  });
});
