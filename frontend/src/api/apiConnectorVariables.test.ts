import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  apiConnectorVariableKeys,
  createApiConnectorVariable,
  deleteApiConnectorVariable,
  listApiConnectorVariableSummaries,
  listApiConnectorVariables,
  reorderApiConnectorVariables,
  updateApiConnectorVariable,
} from './apiConnectorVariables';
import { apiClient } from './client';

vi.mock('./client', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const connectorId = 'c-1';
const base = `/api/v1/api-connectors/${connectorId}/variables`;

describe('apiConnectorVariables', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('builds hierarchical, connector-scoped query keys', () => {
    expect(apiConnectorVariableKeys.all).toEqual(['api-connector-variables']);
    expect(apiConnectorVariableKeys.list(connectorId)).toEqual([
      'api-connector-variables',
      'list',
      connectorId,
    ]);
    expect(apiConnectorVariableKeys.summary(connectorId)).toEqual([
      'api-connector-variables',
      'summary',
      connectorId,
    ]);
  });

  it('unwraps the list envelope', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { content: [{ id: 'v-1' }] } });

    await expect(listApiConnectorVariables(connectorId)).resolves.toEqual([{ id: 'v-1' }]);
    expect(vi.mocked(apiClient.get).mock.calls[0]?.[0]).toBe(base);
  });

  it('reads summaries from the submitter-safe endpoint', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: { content: [{ name: 'nonce' }] } });

    await expect(listApiConnectorVariableSummaries(connectorId)).resolves.toEqual([
      { name: 'nonce' },
    ]);
    expect(vi.mocked(apiClient.get).mock.calls[0]?.[0]).toBe(`${base}/summary`);
  });

  it('posts the create payload', async () => {
    vi.mocked(apiClient.post).mockResolvedValue({ data: { id: 'v-1' } });
    const input = { name: 'sig', kind: 'HMAC' as const, secret: 'k' };

    await createApiConnectorVariable(connectorId, input);

    expect(vi.mocked(apiClient.post).mock.calls[0]?.[0]).toBe(base);
    expect(vi.mocked(apiClient.post).mock.calls[0]?.[1]).toEqual(input);
  });

  it('puts the update payload at the variable path', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: { id: 'v-1' } });

    await updateApiConnectorVariable(connectorId, 'v-1', { name: 'sig', kind: 'CONSTANT' });

    expect(vi.mocked(apiClient.put).mock.calls[0]?.[0]).toBe(`${base}/v-1`);
  });

  it('deletes by id', async () => {
    vi.mocked(apiClient.delete).mockResolvedValue({ data: undefined });

    await deleteApiConnectorVariable(connectorId, 'v-1');

    expect(vi.mocked(apiClient.delete).mock.calls[0]?.[0]).toBe(`${base}/v-1`);
  });

  it('sends the reorder list under variable_ids and unwraps the response', async () => {
    vi.mocked(apiClient.put).mockResolvedValue({ data: { content: [{ id: 'b' }, { id: 'a' }] } });

    await expect(reorderApiConnectorVariables(connectorId, ['b', 'a'])).resolves.toEqual([
      { id: 'b' },
      { id: 'a' },
    ]);
    expect(vi.mocked(apiClient.put).mock.calls[0]?.[0]).toBe(`${base}/order`);
    expect(vi.mocked(apiClient.put).mock.calls[0]?.[1]).toEqual({ variable_ids: ['b', 'a'] });
  });
});
