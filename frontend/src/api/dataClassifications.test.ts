import { describe, expect, it, vi, beforeEach } from 'vitest';

const { get, post, del } = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  del: vi.fn(),
}));

vi.mock('./client', () => ({
  apiClient: { get, post, delete: del },
}));

import * as api from './dataClassifications';
import { dataClassificationKeys } from './dataClassifications';

const tagFixture = {
  id: 'dct-1',
  datasource_id: 'ds-1',
  table_name: 'users',
  column_name: 'email',
  classification: 'PII' as const,
  note: null,
  created_at: '2026-06-01T10:00:00Z',
  updated_at: '2026-06-01T10:00:00Z',
};

describe('api/dataClassifications', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    del.mockReset();
  });

  it('builds hierarchical query keys', () => {
    expect(dataClassificationKeys.list('ds-1')).toEqual(['data-classifications', 'list', 'ds-1']);
    expect(dataClassificationKeys.derivation('ds-1')).toEqual([
      'data-classifications',
      'derivation',
      'ds-1',
    ]);
    expect(dataClassificationKeys.orgAll).toEqual(['data-classifications', 'org']);
  });

  it('listClassificationTags GETs the datasource path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [tagFixture] } });
    const result = await api.listClassificationTags('ds-1');
    expect(get).toHaveBeenCalledWith('/api/v1/datasources/ds-1/classification-tags');
    expect(result).toHaveLength(1);
    expect(result[0]?.classification).toBe('PII');
  });

  it('createClassificationTags POSTs the body and unwraps content', async () => {
    post.mockResolvedValueOnce({ data: { content: [tagFixture] } });
    const input = {
      table_name: 'users',
      column_name: 'email',
      classifications: ['PII' as const],
      apply_masking: true,
    };
    const result = await api.createClassificationTags('ds-1', input);
    expect(post).toHaveBeenCalledWith('/api/v1/datasources/ds-1/classification-tags', input);
    expect(result).toHaveLength(1);
  });

  it('deleteClassificationTag DELETEs the tag path', async () => {
    del.mockResolvedValueOnce({ data: undefined });
    await api.deleteClassificationTag('ds-1', 'dct-1');
    expect(del).toHaveBeenCalledWith('/api/v1/datasources/ds-1/classification-tags/dct-1');
  });

  it('getDerivationPreview GETs the preview path', async () => {
    get.mockResolvedValueOnce({
      data: { suggested_review_posture: {}, masking_suggestions: [] },
    });
    await api.getDerivationPreview('ds-1');
    expect(get).toHaveBeenCalledWith(
      '/api/v1/datasources/ds-1/classification-tags/derivation-preview',
    );
  });

  it('listOrganizationClassifications GETs the admin path and unwraps content', async () => {
    get.mockResolvedValueOnce({ data: { content: [tagFixture] } });
    const result = await api.listOrganizationClassifications();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/data-classifications');
    expect(result).toHaveLength(1);
  });
});
