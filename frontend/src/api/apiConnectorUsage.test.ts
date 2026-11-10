import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiConnectorUsageKeys, getApiConnectorUsage } from './apiConnectorUsage';
import { apiClient } from './client';

vi.mock('./client', () => ({
  apiClient: { get: vi.fn() },
}));

describe('apiConnectorUsage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('builds connector-scoped keys with the range in the key', () => {
    expect(apiConnectorUsageKeys.all).toEqual(['api-connector-usage']);
    expect(apiConnectorUsageKeys.connector('c-1')).toEqual(['api-connector-usage', 'c-1']);
    expect(apiConnectorUsageKeys.usage('c-1')).toEqual(['api-connector-usage', 'c-1', null, null]);
    expect(apiConnectorUsageKeys.usage('c-1', '2026-11-01', '2026-11-10')).toEqual([
      'api-connector-usage',
      'c-1',
      '2026-11-01',
      '2026-11-10',
    ]);
  });

  it('omits the range params for the default current month', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { connector_id: 'c-1', days: [] } });
    const usage = await getApiConnectorUsage('c-1');
    expect(usage.connector_id).toBe('c-1');
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/usage', { params: {} });
  });

  it('sends from and to when given', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { connector_id: 'c-1', days: [] } });
    await getApiConnectorUsage('c-1', { from: '2026-11-01', to: '2026-11-07' });
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/usage', {
      params: { from: '2026-11-01', to: '2026-11-07' },
    });
  });
});
