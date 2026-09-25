import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, put, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  put: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, put, delete: del },
}));

import * as api from './dataBudgets';
import { dataBudgetKeys } from './dataBudgets';
import type { DataBudgetInput } from '@/types/api';

const budget = {
  id: 'b-1',
  datasource_id: 'ds-1',
  name: 'Daily',
  max_rows: 1000,
  window_minutes: 1440,
  breach_action: 'REQUIRE_REVIEW' as const,
  applies_to_roles: [],
  applies_to_group_ids: [],
  applies_to_user_ids: [],
  enabled: true,
  created_at: '2026-09-01T10:00:00Z',
  updated_at: '2026-09-01T10:00:00Z',
};

const input: DataBudgetInput = {
  name: 'Daily',
  max_rows: 1000,
  window_minutes: 1440,
  breach_action: 'REQUIRE_REVIEW',
  enabled: true,
};

describe('api/dataBudgets', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('builds query keys', () => {
    expect(dataBudgetKeys.list('ds-1')).toEqual(['data-budgets', 'list', 'ds-1']);
    expect(dataBudgetKeys.mine('ds-1')).toEqual(['data-budgets', 'me', 'ds-1']);
    expect(dataBudgetKeys.forUser('u-1')).toEqual(['data-budgets', 'user', 'u-1']);
  });

  it('lists budgets for a datasource', async () => {
    get.mockResolvedValue({ data: { content: [budget] } });
    await expect(api.listDataBudgets('ds-1')).resolves.toEqual([budget]);
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/data-budgets');
  });

  it('creates, updates and deletes a budget', async () => {
    post.mockResolvedValue({ data: budget });
    put.mockResolvedValue({ data: budget });
    del.mockResolvedValue({});

    await expect(api.createDataBudget('ds-1', input)).resolves.toEqual(budget);
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/ds-1/data-budgets', input);
    await expect(api.updateDataBudget('ds-1', 'b-1', input)).resolves.toEqual(budget);
    expect(put).toHaveBeenCalledWith('/api/v1/datasources/ds-1/data-budgets/b-1', input);
    await api.deleteDataBudget('ds-1', 'b-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1/data-budgets/b-1');
  });

  it('reads the caller standing and a user standing', async () => {
    const status = { datasource_id: 'ds-1', exhausted: false, budgets: [] };
    get.mockResolvedValueOnce({ data: status });
    await expect(api.getMyDataBudgetStatus('ds-1')).resolves.toEqual(status);
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/data-budgets/me');

    get.mockResolvedValueOnce({ data: { content: [status] } });
    await expect(api.getUserDataBudgetUsage('u-1')).resolves.toEqual([status]);
    expect(get).toHaveBeenCalledWith('/api/v1/admin/users/u-1/data-budget-usage');
  });
});
