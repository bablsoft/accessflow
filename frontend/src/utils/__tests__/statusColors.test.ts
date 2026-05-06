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
    ['FAILED', 'var(--risk-crit)'],
    ['CANCELLED', 'var(--fg-muted)'],
  ])('maps %s to the expected fg token', (status, fg) => {
    expect(statusColor(status).fg).toBe(fg);
  });

  it('returns a complete triple for every status', () => {
    const statuses: QueryStatus[] = [
      'PENDING_AI', 'PENDING_REVIEW', 'APPROVED', 'EXECUTED',
      'REJECTED', 'FAILED', 'CANCELLED',
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
});
