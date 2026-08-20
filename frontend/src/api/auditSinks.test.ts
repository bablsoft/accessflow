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

import * as auditSinkApi from './auditSinks';
import { auditSinkKeys } from './auditSinks';

const sinkFixture = {
  id: 'as-1',
  organization_id: 'org-1',
  name: 'SOC Splunk',
  type: 'SPLUNK_HEC' as const,
  config: {
    url: 'https://splunk.example.com:8088/services/collector/event',
    token: '********',
    index: 'accessflow',
    source: 'accessflow',
  },
  enabled: true,
  cursor_created_at: '2026-08-19T09:00:00Z',
  last_success_at: '2026-08-19T09:05:00Z',
  last_error: null,
  consecutive_failures: 0,
  next_attempt_at: null,
  behind_count: 0,
  behind_count_capped: false,
  created_at: '2026-08-19T08:00:00Z',
  updated_at: '2026-08-19T09:05:00Z',
};

describe('api/auditSinks', () => {
  beforeEach(() => {
    get.mockReset();
    post.mockReset();
    put.mockReset();
    del.mockReset();
  });

  it('auditSinkKeys produce stable factory output', () => {
    expect(auditSinkKeys.all).toEqual(['audit-sinks']);
    expect(auditSinkKeys.lists()).toEqual(['audit-sinks', 'list']);
  });

  it('listAuditSinks GETs /admin/audit-sinks', async () => {
    get.mockResolvedValueOnce({ data: [sinkFixture] });
    const result = await auditSinkApi.listAuditSinks();
    expect(get).toHaveBeenCalledWith('/api/v1/admin/audit-sinks');
    expect(result).toHaveLength(1);
    expect(result[0]?.type).toBe('SPLUNK_HEC');
  });

  it('createAuditSink POSTs the snake_case body', async () => {
    post.mockResolvedValueOnce({ data: sinkFixture });
    const input = {
      name: 'SOC Splunk',
      type: 'SPLUNK_HEC' as const,
      config: { url: 'https://splunk.example.com:8088', token: 'hec-token' },
    };
    await auditSinkApi.createAuditSink(input);
    expect(post).toHaveBeenCalledWith('/api/v1/admin/audit-sinks', input);
  });

  it('updateAuditSink PUTs the partial body to the sink path', async () => {
    put.mockResolvedValueOnce({ data: sinkFixture });
    await auditSinkApi.updateAuditSink('as-1', {
      name: 'SOC Splunk v2',
      enabled: false,
      config: { token: '********' },
    });
    expect(put).toHaveBeenCalledWith('/api/v1/admin/audit-sinks/as-1', {
      name: 'SOC Splunk v2',
      enabled: false,
      config: { token: '********' },
    });
  });

  it('deleteAuditSink DELETEs the sink path', async () => {
    del.mockResolvedValueOnce({});
    await auditSinkApi.deleteAuditSink('as-1');
    expect(del).toHaveBeenCalledWith('/api/v1/admin/audit-sinks/as-1');
  });

  it('testAuditSink POSTs to the /test sub-resource with no body', async () => {
    post.mockResolvedValueOnce({ data: { status: 'OK', detail: 'Test event delivered' } });
    const result = await auditSinkApi.testAuditSink('as-1');
    expect(post).toHaveBeenCalledWith('/api/v1/admin/audit-sinks/as-1/test');
    expect(result.status).toBe('OK');
  });
});
