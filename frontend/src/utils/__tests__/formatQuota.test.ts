import { describe, expect, it } from 'vitest';
import { formatQuota } from '../formatQuota';

// Minimal stand-in for i18next's t: returns a sentinel for the unlimited key.
const t = ((key: string) => (key === 'admin.organizations.unlimited' ? 'Unlimited' : key)) as never;

describe('formatQuota', () => {
  it('renders a positive limit as its number', () => {
    expect(formatQuota(t, 5)).toBe('5');
  });

  it('treats null/undefined/0/negative as unlimited', () => {
    expect(formatQuota(t, null)).toBe('Unlimited');
    expect(formatQuota(t, undefined)).toBe('Unlimited');
    expect(formatQuota(t, 0)).toBe('Unlimited');
    expect(formatQuota(t, -1)).toBe('Unlimited');
  });
});
