import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, delete: del },
}));

import * as api from './apiConnectorClassifications';
import { apiConnectorClassificationKeys } from './apiConnectorClassifications';

const tagFixture = {
  id: 't-1',
  connector_id: 'c-1',
  operation_id: null,
  field_ref: 'user.ssn',
  matcher_type: 'JSON_PATH' as const,
  classification: 'PII' as const,
  note: null,
  created_at: '2026-06-01T10:00:00Z',
  updated_at: '2026-06-01T10:00:00Z',
};

describe('api/apiConnectorClassifications', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(apiConnectorClassificationKeys.list('c-1')).toEqual([
      'api-connector-classifications',
      'list',
      'c-1',
    ]);
    expect(apiConnectorClassificationKeys.derivation('c-1')).toEqual([
      'api-connector-classifications',
      'derivation',
      'c-1',
    ]);
  });

  it('list GETs the connector-scoped path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [tagFixture] } });
    const result = await api.listApiConnectorClassificationTags('c-1');
    expect(get).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/classification-tags');
    expect(result).toHaveLength(1);
    expect(result[0]?.classification).toBe('PII');
  });

  it('create POSTs the snake_case body and unwraps content', async () => {
    post.mockResolvedValueOnce({ data: { content: [tagFixture] } });
    const input = {
      matcher_type: 'JSON_PATH' as const,
      field_ref: 'user.ssn',
      classifications: ['PII' as const],
      apply_masking: true,
    };
    const result = await api.createApiConnectorClassificationTags('c-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/classification-tags', input);
    expect(result).toHaveLength(1);
  });

  it('delete DELETEs the tag path', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await api.deleteApiConnectorClassificationTag('c-1', 't-1');
    expect(del).toHaveBeenCalledWith('/api/v1/api-connectors/c-1/classification-tags/t-1');
  });

  it('derivation preview GETs the derivation-preview path', async () => {
    get.mockResolvedValueOnce({ data: { suggested_review_posture: {}, masking_suggestions: [] } });
    await api.getApiConnectorDerivationPreview('c-1');
    expect(get).toHaveBeenCalledWith(
      '/api/v1/api-connectors/c-1/classification-tags/derivation-preview',
    );
  });
});
