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

import * as api from './schemaChange';

const page = { content: [], page: 0, size: 20, total_elements: 0, total_pages: 0 };

describe('api/schemaChange', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('keys nest details, ladders and promotions under the set', () => {
    expect(api.schemaChangeKeys.ladder('s-1').slice(0, 4)).toEqual(api.schemaChangeKeys.detail('s-1'));
    expect(api.schemaChangeKeys.promotions('s-1').slice(0, 3)).toEqual(api.schemaChangeKeys.details());
    expect(api.schemaChangeKeys.list({ status: 'DRAFT' }).slice(0, 3)).toEqual(api.schemaChangeKeys.lists());
    expect(api.schemaChangeKeys.driftFindings({}).slice(0, 2)).toEqual(api.schemaChangeKeys.drift());
    expect(api.schemaChangeKeys.driftScans({}).slice(0, 2)).toEqual(api.schemaChangeKeys.drift());
    expect(api.schemaChangeKeys.pipelines()[0]).toBe(api.schemaChangeKeys.all[0]);
  });

  it('listSchemaChangePipelines reads the pipeline ladder listing', async () => {
    get.mockResolvedValueOnce({ data: [] });
    await api.listSchemaChangePipelines();
    expect(get).toHaveBeenCalledWith('/api/v1/schema-change-pipelines');
  });

  it('listSchemaChangeSets passes only the set filters', async () => {
    get.mockResolvedValueOnce({ data: page });
    await api.listSchemaChangeSets({ page: 1, size: 20, pipeline_id: 'p-1', status: 'ACTIVE' });
    expect(get).toHaveBeenCalledWith('/api/v1/schema-change-sets', {
      params: { page: 1, size: 20, pipeline_id: 'p-1', status: 'ACTIVE' },
    });
  });

  it('listSchemaChangeSets omits absent and empty filters', async () => {
    get.mockResolvedValueOnce({ data: page });
    await api.listSchemaChangeSets({ pipeline_id: '' });
    expect(get).toHaveBeenCalledWith('/api/v1/schema-change-sets', { params: {} });
    get.mockResolvedValueOnce({ data: page });
    await api.listSchemaChangeSets();
    expect(get).toHaveBeenLastCalledWith('/api/v1/schema-change-sets', { params: {} });
  });

  it('get/create/update/delete a change set', async () => {
    get.mockResolvedValueOnce({ data: { id: 's-1' } });
    expect((await api.getSchemaChangeSet('s-1')).id).toBe('s-1');
    expect(get).toHaveBeenCalledWith('/api/v1/schema-change-sets/s-1');

    const input = { pipeline_id: 'p-1', name: 'orders', statements: [{ sql_text: 'CREATE TABLE t (id INT)' }] };
    post.mockResolvedValueOnce({ data: { id: 's-1' } });
    await api.createSchemaChangeSet(input);
    expect(post).toHaveBeenCalledWith('/api/v1/schema-change-sets', input);

    put.mockResolvedValueOnce({ data: { id: 's-1' } });
    await api.updateSchemaChangeSet('s-1', { status: 'ARCHIVED' });
    expect(put).toHaveBeenCalledWith('/api/v1/schema-change-sets/s-1', { status: 'ARCHIVED' });

    del.mockResolvedValueOnce({});
    await api.deleteSchemaChangeSet('s-1');
    expect(del).toHaveBeenCalledWith('/api/v1/schema-change-sets/s-1');
  });

  it('replaceSchemaChangeSetStatements wraps each text', async () => {
    put.mockResolvedValueOnce({ data: { id: 's-1' } });
    await api.replaceSchemaChangeSetStatements('s-1', ['A', 'B']);
    expect(put).toHaveBeenCalledWith('/api/v1/schema-change-sets/s-1/statements', {
      statements: [{ sql_text: 'A' }, { sql_text: 'B' }],
    });
  });

  it('ladder, promotions, promote and cancel', async () => {
    get.mockResolvedValueOnce({ data: { rungs: [] } });
    await api.getSchemaChangeLadder('s-1');
    expect(get).toHaveBeenCalledWith('/api/v1/schema-change-sets/s-1/ladder');

    get.mockResolvedValueOnce({ data: [] });
    await api.listSchemaChangePromotions('s-1');
    expect(get).toHaveBeenLastCalledWith('/api/v1/schema-change-sets/s-1/promotions');

    post.mockResolvedValueOnce({ data: { id: 'pr-1' } });
    await api.promoteSchemaChangeSet('s-1', 'e-1');
    expect(post).toHaveBeenCalledWith('/api/v1/schema-change-sets/s-1/promotions', {
      environment_id: 'e-1',
    });

    post.mockResolvedValueOnce({});
    await api.cancelSchemaChangePromotion('pr-1');
    expect(post).toHaveBeenLastCalledWith('/api/v1/schema-change-promotions/pr-1/cancel');
  });

  it('drift findings, scans, scan-now and acknowledge', async () => {
    get.mockResolvedValueOnce({ data: page });
    await api.listSchemaDriftFindings({ environment_id: 'e-1', status: 'OPEN' });
    expect(get).toHaveBeenCalledWith('/api/v1/schema-drift/findings', {
      params: { environment_id: 'e-1', status: 'OPEN' },
    });

    get.mockResolvedValueOnce({ data: page });
    await api.listSchemaDriftScans({ pipeline_id: 'p-1', size: 100 });
    expect(get).toHaveBeenLastCalledWith('/api/v1/schema-drift/scans', {
      params: { pipeline_id: 'p-1', size: 100 },
    });

    get.mockResolvedValueOnce({ data: page });
    await api.listSchemaDriftFindings();
    get.mockResolvedValueOnce({ data: page });
    await api.listSchemaDriftScans();
    expect(get).toHaveBeenLastCalledWith('/api/v1/schema-drift/scans', { params: {} });

    post.mockResolvedValueOnce({ data: { id: 'sc-1' } });
    await api.requestSchemaDriftScan('e-1');
    expect(post).toHaveBeenCalledWith('/api/v1/schema-drift/scans', { environment_id: 'e-1' });

    post.mockResolvedValueOnce({ data: { id: 'f-1' } });
    await api.acknowledgeSchemaDriftFinding('f-1');
    expect(post).toHaveBeenLastCalledWith('/api/v1/schema-drift/findings/f-1/acknowledge');
  });
});
