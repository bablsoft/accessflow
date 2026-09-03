import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));

vi.mock('./client', () => ({
  apiClient: { get },
}));

import { fetchUpdateStatus, updateKeys } from './updates';

describe('api/updates', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(updateKeys.all).toEqual(['updates']);
    expect(updateKeys.status()).toEqual(['updates', 'status']);
  });

  it('fetchUpdateStatus GETs the system endpoint and returns the snapshot', async () => {
    const snapshot = {
      current_version: '2.4.0',
      latest_version: '2.5.0',
      update_available: true,
      changelog_url: 'https://accessflow.io/changelog/#v2-5-0',
      checked_at: '2026-09-20T08:00:00Z',
      status: 'UPDATE_AVAILABLE',
    };
    get.mockResolvedValueOnce({ data: snapshot });
    await expect(fetchUpdateStatus()).resolves.toEqual(snapshot);
    expect(get).toHaveBeenCalledWith('/api/v1/system/update-status');
  });
});
