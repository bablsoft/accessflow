import { describe, expect, it } from 'vitest';
import { formatDurationCompact, remainingTtlMs } from '../accessTtl';

describe('remainingTtlMs', () => {
  const now = Date.parse('2026-06-01T12:00:00Z');

  it('returns null when expiresAt is missing', () => {
    expect(remainingTtlMs(null, now)).toBeNull();
    expect(remainingTtlMs(undefined, now)).toBeNull();
  });

  it('returns null for an unparseable timestamp', () => {
    expect(remainingTtlMs('not-a-date', now)).toBeNull();
  });

  it('returns positive ms for a future expiry', () => {
    expect(remainingTtlMs('2026-06-01T15:00:00Z', now)).toBe(3 * 3_600_000);
  });

  it('returns negative ms for a past expiry', () => {
    expect(remainingTtlMs('2026-06-01T11:00:00Z', now)).toBe(-3_600_000);
  });
});

describe('formatDurationCompact', () => {
  it('shows <1m below a minute', () => {
    expect(formatDurationCompact(30_000)).toBe('<1m');
    expect(formatDurationCompact(0)).toBe('<1m');
    expect(formatDurationCompact(-5)).toBe('<1m');
  });

  it('shows minutes only', () => {
    expect(formatDurationCompact(8 * 60_000)).toBe('8m');
  });

  it('shows hours and minutes', () => {
    expect(formatDurationCompact((5 * 60 + 12) * 60_000)).toBe('5h 12m');
  });

  it('shows days and hours, capped at two units', () => {
    expect(formatDurationCompact((3 * 1440 + 4 * 60 + 30) * 60_000)).toBe('3d 4h');
  });
});
