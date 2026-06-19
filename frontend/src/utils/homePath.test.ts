import { describe, expect, it } from 'vitest';
import { homePathForRole } from './homePath';

describe('homePathForRole', () => {
  it('sends AUDITOR to the auditor dashboard', () => {
    expect(homePathForRole('AUDITOR')).toBe('/admin/auditor');
  });

  it('sends every other role to the editor', () => {
    expect(homePathForRole('ADMIN')).toBe('/editor');
    expect(homePathForRole('ANALYST')).toBe('/editor');
    expect(homePathForRole('REVIEWER')).toBe('/editor');
    expect(homePathForRole('READONLY')).toBe('/editor');
    expect(homePathForRole(undefined)).toBe('/editor');
  });
});
