import { describe, expect, it } from 'vitest';
import { statusColor, statusLabel } from '../statusColors';
import type { QueryStatus } from '@/types/api';

describe('statusColor', () => {
  it.each<[QueryStatus, string]>([
    ['PENDING_AI', 'var(--status-info)'],
    ['PENDING_REVIEW', 'var(--status-info)'],
    ['APPROVED', 'var(--risk-low)'],
    ['EXECUTED', 'var(--risk-low)'],
    ['REJECTED', 'var(--risk-crit)'],
    ['TIMED_OUT', 'var(--status-warn)'],
    ['FAILED', 'var(--risk-crit)'],
    ['CANCELLED', 'var(--fg-muted)'],
  ])('maps %s to the expected fg token', (status, fg) => {
    expect(statusColor(status).fg).toBe(fg);
  });

  it('TIMED_OUT is visually distinct from REJECTED', () => {
    expect(statusColor('TIMED_OUT').fg).not.toBe(statusColor('REJECTED').fg);
  });

  it('returns a complete triple for every status', () => {
    const statuses: QueryStatus[] = [
      'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED',
      'REJECTED', 'TIMED_OUT', 'FAILED', 'CANCELLED',
    ];
    for (const s of statuses) {
      const c = statusColor(s);
      expect(c.fg).toBeDefined();
      expect(c.bg).toBeDefined();
      expect(c.border).toBeDefined();
    }
  });
});

describe('statusLabel', () => {
  it('replaces underscores with spaces', () => {
    expect(statusLabel('PENDING_AI')).toBe('PENDING AI');
    expect(statusLabel('PENDING_REVIEW')).toBe('PENDING REVIEW');
  });

  it('returns single-word statuses unchanged', () => {
    expect(statusLabel('APPROVED')).toBe('APPROVED');
  });

  it('formats TIMED_OUT with a space', () => {
    expect(statusLabel('TIMED_OUT')).toBe('TIMED OUT');
  });
});
