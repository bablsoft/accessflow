import { describe, expect, it } from 'vitest';
import { fmtDate, fmtNum, timeAgo } from '../dateFormat';
import { DEMO_NOW } from '@/mocks/data';

describe('timeAgo', () => {
  it('returns "just now" for sub-minute differences', () => {
    expect(timeAgo(new Date(DEMO_NOW - 30 * 1000))).toBe('just now');
  });

  it('returns minutes for sub-hour differences', () => {
    expect(timeAgo(new Date(DEMO_NOW - 5 * 60 * 1000))).toBe('5m ago');
  });

  it('returns hours for sub-day differences', () => {
    expect(timeAgo(new Date(DEMO_NOW - 3 * 60 * 60 * 1000))).toBe('3h ago');
  });

  it('returns days for sub-week differences', () => {
    expect(timeAgo(new Date(DEMO_NOW - 2 * 24 * 60 * 60 * 1000))).toBe('2d ago');
  });

  it('falls back to a date for week+ differences', () => {
    const result = timeAgo(new Date(DEMO_NOW - 30 * 24 * 60 * 60 * 1000));
    expect(result).toMatch(/\w+ \d+/);
  });
});

describe('fmtDate', () => {
  it('formats an ISO date as a readable string', () => {
    const result = fmtDate('2026-05-04T10:30:00Z');
    expect(result).toMatch(/2026/);
    expect(result).toMatch(/May/);
  });
});

describe('fmtNum', () => {
  it('returns "—" for null', () => {
    expect(fmtNum(null)).toBe('—');
  });

  it('returns "—" for undefined', () => {
    expect(fmtNum(undefined)).toBe('—');
  });

  it('returns localized number with separators', () => {
    expect(fmtNum(1234567)).toBe('1,234,567');
  });

  it('handles zero', () => {
    expect(fmtNum(0)).toBe('0');
  });
});
