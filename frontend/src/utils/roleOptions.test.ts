import { describe, expect, it } from 'vitest';
import i18n from '@/i18n';
import type { RoleSummary } from '@/types/api';
import { roleDisplayName, roleSelectOptions } from './roleOptions';

function role(partial: Partial<RoleSummary>): RoleSummary {
  return {
    id: 'r-x',
    organization_id: 'org-1',
    name: 'X',
    description: null,
    system: false,
    permissions: [],
    assigned_user_count: 0,
    created_at: '2026-07-01T00:00:00Z',
    updated_at: '2026-07-01T00:00:00Z',
    ...partial,
  };
}

const t = i18n.t.bind(i18n);

describe('roleDisplayName', () => {
  it('localizes system role names', () => {
    expect(roleDisplayName(t, role({ name: 'READONLY', system: true }))).toBe('Read-only');
  });

  it('renders custom role names verbatim', () => {
    expect(roleDisplayName(t, role({ name: 'Release Manager', system: false }))).toBe(
      'Release Manager',
    );
  });
});

describe('roleSelectOptions', () => {
  const roles = [
    role({ id: 'r-1', name: 'ADMIN', system: true }),
    role({ id: 'r-2', name: 'Release Manager', system: false }),
  ];

  it('uses the role id as value when by=id', () => {
    expect(roleSelectOptions(roles, t, 'id')).toEqual([
      { value: 'r-1', label: 'Admin' },
      { value: 'r-2', label: 'Release Manager' },
    ]);
  });

  it('uses the role name as value when by=name', () => {
    expect(roleSelectOptions(roles, t, 'name')).toEqual([
      { value: 'ADMIN', label: 'Admin' },
      { value: 'Release Manager', label: 'Release Manager' },
    ]);
  });

  it('returns an empty array for no roles', () => {
    expect(roleSelectOptions([], t, 'name')).toEqual([]);
  });
});
