import { describe, expect, it } from 'vitest';
import type { AuthUser } from '@/api/auth';
import { activeNavEntry, flattenNav, visibleNavGroups, type NavGroup } from '../navModel';

const user = (over: Partial<AuthUser> = {}): AuthUser => ({
  id: 'u',
  email: 'u@example.com',
  display_name: 'U',
  role: 'ANALYST',
  role_id: null,
  permissions: ['QUERY_SUBMIT_SELECT'],
  auth_provider: 'LOCAL',
  totp_enabled: false,
  platform_admin: false,
  preferred_language: null,
  ...over,
});

const groups: NavGroup[] = [
  { id: 'general', items: [{ id: 'reviews', to: '/reviews', label: 'Reviews', icon: null, permissions: ['QUERY_SUBMIT_SELECT'] }] },
  {
    id: 'work',
    label: 'Work',
    subgroups: [
      {
        id: 'db',
        label: 'Database',
        items: [
          { id: 'att', to: '/reviews/attestations', label: 'Attestations', icon: null, permissions: ['QUERY_SUBMIT_SELECT'] },
          { id: 'ds', to: '/datasources', label: 'Datasources', icon: null, permissions: ['DATASOURCE_MANAGE'] },
        ],
      },
      {
        id: 'api',
        label: 'API',
        domain: 'apis',
        items: [{ id: 'api', to: '/api-requests', label: 'API requests', icon: null, permissions: ['QUERY_SUBMIT_SELECT'] }],
      },
    ],
  },
  { id: 'platform', label: 'Platform', items: [{ id: 'orgs', to: '/admin/organizations', label: 'Orgs', icon: null, permissions: [], platformAdmin: true }] },
  { id: 'empty' },
];

describe('visibleNavGroups', () => {
  it('filters by permission and drops empty sub-sections and groups', () => {
    const visible = visibleNavGroups(groups, user());
    expect(visible.map((g) => g.id)).toEqual(['general', 'work']);
    expect(flattenNav(visible).map((e) => e.item.id)).toEqual(['reviews', 'att', 'api']);
  });

  it('shows platform entries to platform admins and hides a disabled governance domain', () => {
    const visible = visibleNavGroups(
      groups,
      user({ platform_admin: true, governs_apis: false }),
    );
    const ids = flattenNav(visible).map((e) => e.item.id);
    expect(ids).toContain('orgs');
    expect(ids).not.toContain('api');
  });
});

describe('flattenNav / activeNavEntry', () => {
  const entries = flattenNav(visibleNavGroups(groups, user()));

  it('carries the headings each destination sits under', () => {
    expect(entries[0]?.groupLabel).toBeUndefined();
    expect(entries[0]?.subLabel).toBeUndefined();
    expect(entries[1]).toMatchObject({ groupLabel: 'Work', subLabel: 'Database' });
  });

  it('picks the most specific match, including for nested detail routes', () => {
    expect(activeNavEntry(entries, '/reviews')?.item.id).toBe('reviews');
    expect(activeNavEntry(entries, '/reviews/attestations/7')?.item.id).toBe('att');
    expect(activeNavEntry(entries, '/reviewsx')).toBeNull();
  });
});
