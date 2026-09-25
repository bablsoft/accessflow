import { describe, expect, it } from 'vitest';
import {
  BYTES_CAP_MIN,
  byteUnitFactor,
  pickUnit,
  supportsBytesCap,
  toBytesCapUpdate,
} from '../bytesCap';

describe('bytesCap', () => {
  it('mirrors the backend @Min(1)', () => {
    expect(BYTES_CAP_MIN).toBe(1);
  });

  it('is supported on the bytes-reporting warehouses only', () => {
    expect(supportsBytesCap('BIGQUERY')).toBe(true);
    expect(supportsBytesCap('SNOWFLAKE')).toBe(true);
    expect(supportsBytesCap('DATABRICKS')).toBe(true);
    expect(supportsBytesCap('POSTGRESQL')).toBe(false);
    expect(supportsBytesCap('MONGODB')).toBe(false);
  });

  it('sends a set cap and clears an emptied one only when one was stored', () => {
    expect(toBytesCapUpdate(500, null)).toEqual({ max_bytes_scanned_per_query: 500 });
    expect(toBytesCapUpdate(null, 500)).toEqual({ clear_max_bytes_scanned_per_query: true });
    expect(toBytesCapUpdate(undefined, undefined)).toEqual({});
    expect(toBytesCapUpdate(null, null)).toEqual({});
  });

  it('picks the largest unit a value fills at least once', () => {
    expect(pickUnit(undefined)).toBe('GB');
    expect(pickUnit(0)).toBe('GB');
    expect(pickUnit(500)).toBe('MB');
    expect(pickUnit(5e6)).toBe('MB');
    expect(pickUnit(2e9)).toBe('GB');
    expect(pickUnit(2e12)).toBe('TB');
    expect(pickUnit(3e15)).toBe('PB');
  });

  it('maps each unit to its decimal factor', () => {
    expect(byteUnitFactor('MB')).toBe(1e6);
    expect(byteUnitFactor('TB')).toBe(1e12);
  });
});
