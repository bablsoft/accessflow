import { describe, expect, it } from 'vitest';
import { homePathForRole } from './homePath';

describe('homePathForRole', () => {
  it('sends AUDITOR to the auditor dashboard', () => {
    expect(homePathForRole('AUDITOR')).toBe('/admin/auditor');
  });

  it('sends every other role to the personalized dashboard', () => {
    expect(homePathForRole('ADMIN')).toBe('/dashboard');
    expect(homePathForRole('ANALYST')).toBe('/dashboard');
    expect(homePathForRole('REVIEWER')).toBe('/dashboard');
    expect(homePathForRole('READONLY')).toBe('/dashboard');
    expect(homePathForRole(undefined)).toBe('/dashboard');
  });
});
