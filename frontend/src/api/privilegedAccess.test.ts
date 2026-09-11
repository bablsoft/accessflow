import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { PrivilegedAccessPage } from '@/types/api';

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn() }));

vi.mock('./client', () => ({ apiClient: { get: getMock } }));

const { listPrivilegedAccess, privilegedAccessKeys } = await import('./privilegedAccess');

const EMPTY: PrivilegedAccessPage = {
  content: [],
  page: 0,
  size: 20,
  total_elements: 0,
  total_pages: 0,
};

describe('privilegedAccess api', () => {
  beforeEach(() => {
    getMock.mockReset();
  });

  it('sends the filters as query params and returns the page envelope', async () => {
    getMock.mockResolvedValue({ data: EMPTY });

    const page = await listPrivilegedAccess({ page: 1, size: 20, kind: 'BREAK_GLASS', user_id: 'u-1' });

    expect(getMock).toHaveBeenCalledWith('/api/v1/admin/privileged-access', {
      params: { page: 1, size: 20, kind: 'BREAK_GLASS', user_id: 'u-1' },
    });
    expect(page).toEqual(EMPTY);
  });

  it('builds hierarchical, domain-prefixed query keys', () => {
    expect(privilegedAccessKeys.all).toEqual(['privileged-access']);
    expect(privilegedAccessKeys.reports()).toEqual(['privileged-access', 'report']);
    expect(privilegedAccessKeys.report({ kind: 'QUERY_ADMIN' })).toEqual([
      'privileged-access',
      'report',
      { kind: 'QUERY_ADMIN' },
    ]);
  });
});
