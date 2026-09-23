import { beforeEach, describe, expect, it, vi } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get },
}));

import { listQueryTemplates } from './queryTemplates';

const EMPTY_PAGE = { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 };

describe('api/queryTemplates', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('listQueryTemplates sends the documented snake_case datasource filter', async () => {
    get.mockResolvedValueOnce({ data: EMPTY_PAGE });

    await listQueryTemplates({
      page: 1,
      size: 50,
      datasourceId: 'ds-1',
      tag: 'ops',
      visibility: 'TEAM',
      q: 'top',
    });

    expect(get).toHaveBeenCalledWith('/api/v1/query-templates', {
      params: { page: 1, size: 50, datasource_id: 'ds-1', tag: 'ops', visibility: 'TEAM', q: 'top' },
    });
  });

  it('listQueryTemplates omits unset filters', async () => {
    get.mockResolvedValueOnce({ data: EMPTY_PAGE });

    await listQueryTemplates();

    expect(get).toHaveBeenCalledWith('/api/v1/query-templates', { params: {} });
  });
});
