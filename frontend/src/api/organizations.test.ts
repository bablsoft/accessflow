import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put },
}));

import * as orgApi from './organizations';

const orgFixture = {
  id: 'org-1',
  name: 'Acme',
  slug: 'acme',
  disabled: false,
  max_datasources: 5,
  max_users: 20,
  max_queries_per_day: 1000,
  created_at: '2026-01-01T00:00:00Z',
  updated_at: '2026-01-01T00:00:00Z',
};

describe('api/organizations', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
  });

  it('listOrganizations passes paging params and returns the page', async () => {
    get.mockResolvedValue({ data: { content: [orgFixture], page: 0, size: 100, total_elements: 1, total_pages: 1 } });
    const page = await orgApi.listOrganizations({ page: 0, size: 100 });
    expect(get).toHaveBeenCalledWith('/api/v1/platform/organizations', { params: { page: 0, size: 100 } });
    expect(page.content).toHaveLength(1);
  });

  it('getOrganization fetches by id', async () => {
    get.mockResolvedValue({ data: orgFixture });
    const org = await orgApi.getOrganization('org-1');
    expect(get).toHaveBeenCalledWith('/api/v1/platform/organizations/org-1');
    expect(org.slug).toBe('acme');
  });

  it('getOrganizationUsage hits the usage endpoint', async () => {
    get.mockResolvedValue({ data: { organization_id: 'org-1', datasource_count: 1, max_datasources: 5, user_count: 2, max_users: 20, queries_last_24h: 3, max_queries_per_day: 1000 } });
    const usage = await orgApi.getOrganizationUsage('org-1');
    expect(get).toHaveBeenCalledWith('/api/v1/platform/organizations/org-1/usage');
    expect(usage.user_count).toBe(2);
  });

  it('createOrganization posts the payload', async () => {
    post.mockResolvedValue({ data: orgFixture });
    await orgApi.createOrganization({ name: 'Acme', slug: 'acme' });
    expect(post).toHaveBeenCalledWith('/api/v1/platform/organizations', { name: 'Acme', slug: 'acme' });
  });

  it('updateOrganization puts to the id endpoint', async () => {
    put.mockResolvedValue({ data: orgFixture });
    await orgApi.updateOrganization('org-1', { name: 'New' });
    expect(put).toHaveBeenCalledWith('/api/v1/platform/organizations/org-1', { name: 'New' });
  });

  it('disable and enable post to the action endpoints', async () => {
    post.mockResolvedValue({ data: undefined });
    await orgApi.disableOrganization('org-1');
    expect(post).toHaveBeenCalledWith('/api/v1/platform/organizations/org-1/disable');
    await orgApi.enableOrganization('org-1');
    expect(post).toHaveBeenCalledWith('/api/v1/platform/organizations/org-1/enable');
  });

  it('organizationKeys are hierarchical', () => {
    expect(orgApi.organizationKeys.all).toEqual(['organizations']);
    expect(orgApi.organizationKeys.detail('x')).toEqual(['organizations', 'detail', 'x']);
    expect(orgApi.organizationKeys.usage('x')).toEqual(['organizations', 'usage', 'x']);
  });
});
