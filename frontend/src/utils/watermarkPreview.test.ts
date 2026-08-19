import { describe, expect, it } from 'vitest';
import { watermarkFooter, watermarkHeader } from './watermarkPreview';

// Mirrors backend ResultExportWatermark — these strings must match byte-for-byte.
describe('watermarkPreview', () => {
  const queryId = '3fa85f64-5717-4562-b3fc-2c963f66afa6';

  it('renders the header with email, UTC second-precision timestamp, and query id', () => {
    const stamp = new Date('2026-08-18T09:30:00.123Z');
    expect(watermarkHeader('jane@example.com', stamp, queryId)).toBe(
      `AccessFlow export | jane@example.com | 2026-08-18T09:30:00Z | query ${queryId}`,
    );
  });

  it('truncates sub-second precision instead of rounding', () => {
    const stamp = new Date('2026-08-18T09:30:59.999Z');
    expect(watermarkHeader('a@b.c', stamp, queryId)).toContain('2026-08-18T09:30:59Z');
  });

  it('renders the footer without a cap suffix when uncapped', () => {
    expect(watermarkFooter(42)).toBe('AccessFlow export end | 42 rows');
    expect(watermarkFooter(42, null)).toBe('AccessFlow export end | 42 rows');
  });

  it('appends the cap provenance when capped', () => {
    expect(watermarkFooter(1000, 1000)).toBe(
      'AccessFlow export end | 1000 rows | capped at 1000',
    );
  });

  it('renders zero rows literally', () => {
    expect(watermarkFooter(0)).toBe('AccessFlow export end | 0 rows');
  });
});
