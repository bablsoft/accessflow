import { describe, expect, it } from 'vitest';
import type { RequestGroupItem } from '@/types/api';
import { memberFromItem, memberToBody, memberValid, newKey, newMember } from './groupBuilder';

function persistedItem(overrides: Partial<RequestGroupItem> = {}): RequestGroupItem {
  return {
    id: 'i-1',
    sequence_order: 0,
    target_kind: 'QUERY',
    datasource_id: 'ds-1',
    datasource_name: 'prod',
    sql_text: 'SELECT 1',
    query_type: 'SELECT',
    transactional: true,
    api_connector_id: null,
    api_connector_name: null,
    operation_id: null,
    verb: null,
    request_path: null,
    request_headers: null,
    query_params: null,
    body_type: null,
    request_content_type: null,
    request_body: null,
    form_fields: null,
    binary_filename: null,
    ai_analysis_id: null,
    ai_risk_level: 'LOW',
    ai_risk_score: 10,
    ai_analysis: null,
    status: 'PENDING',
    response_status_code: null,
    rows_affected: null,
    error_message: null,
    duration_ms: null,
    executed_at: null,
    ...overrides,
  };
}

describe('groupBuilder', () => {
  it('newKey returns unique keys', () => {
    expect(newKey()).not.toBe(newKey());
  });

  it('newMember seeds kind-appropriate defaults', () => {
    const q = newMember('QUERY');
    expect(q.targetKind).toBe('QUERY');
    expect(q.verb).toBe('GET');
    const a = newMember('API_CALL');
    expect(a.targetKind).toBe('API_CALL');
  });

  it('memberToBody serializes a QUERY member', () => {
    const m = newMember('QUERY');
    m.datasourceId = 'ds-1';
    m.sqlText = 'SELECT 1';
    m.transactional = true;
    expect(memberToBody(m)).toEqual({
      target_kind: 'QUERY',
      datasource_id: 'ds-1',
      sql_text: 'SELECT 1',
      transactional: true,
    });
  });

  it('memberToBody serializes an API_CALL member from its full composition', () => {
    const m = newMember('API_CALL');
    m.connectorId = 'c-1';
    m.verb = 'POST';
    m.requestPath = '/v1/things';
    m.composition.rawBody = '{"a":1}';
    m.composition.headers = [{ key: 'X-Trace', value: '1' }];
    m.composition.queryParams = [{ key: 'dryRun', value: 'true' }];
    expect(memberToBody(m)).toEqual({
      target_kind: 'API_CALL',
      transactional: false,
      api_connector_id: 'c-1',
      operation_id: null,
      verb: 'POST',
      request_path: '/v1/things',
      body_type: 'RAW',
      request_content_type: 'application/json',
      request_body: '{"a":1}',
      request_headers: { 'X-Trace': '1' },
      query_params: { dryRun: 'true' },
      form_fields: [],
      binary_filename: null,
    });
  });

  it('memberToBody serializes form-data fields', () => {
    const m = newMember('API_CALL');
    m.connectorId = 'c-1';
    m.requestPath = '/v1/things';
    m.composition.bodyType = 'FORM_DATA';
    m.composition.formFields = [
      { key: 'note', type: 'TEXT', value: 'hello' },
      { key: 'doc', type: 'FILE', value: 'AAA=', filename: 'a.txt', content_type: 'text/plain' },
    ];
    const body = memberToBody(m);
    expect(body.body_type).toBe('FORM_DATA');
    expect(body.request_body).toBeNull();
    expect(body.form_fields).toEqual([
      { key: 'note', type: 'TEXT', value: 'hello', filename: null, content_type: null },
      { key: 'doc', type: 'FILE', value: 'AAA=', filename: 'a.txt', content_type: 'text/plain' },
    ]);
  });

  it('memberToBody sends a null RAW body when blank', () => {
    const m = newMember('API_CALL');
    m.connectorId = 'c-1';
    m.requestPath = '/v1/things';
    const body = memberToBody(m);
    expect(body.body_type).toBe('RAW');
    expect(body.request_body).toBeNull();
  });

  it('memberValid enforces QUERY required fields', () => {
    const m = newMember('QUERY');
    expect(memberValid(m)).toBe(false);
    m.datasourceId = 'ds-1';
    m.sqlText = 'SELECT 1';
    expect(memberValid(m)).toBe(true);
    m.sqlText = 'x'.repeat(100001);
    expect(memberValid(m)).toBe(false);
  });

  it('memberValid enforces API_CALL required fields and limits', () => {
    const m = newMember('API_CALL');
    expect(memberValid(m)).toBe(false);
    m.connectorId = 'c-1';
    m.requestPath = '/v1/x';
    expect(memberValid(m)).toBe(true);
    m.verb = 'x'.repeat(17);
    expect(memberValid(m)).toBe(false);
  });

  it('memberFromItem hydrates from a persisted item', () => {
    const m = memberFromItem(persistedItem());
    expect(m.key).toBe('i-1');
    expect(m.datasourceId).toBe('ds-1');
    expect(m.sqlText).toBe('SELECT 1');
    expect(m.transactional).toBe(true);
    expect(m.aiRiskLevel).toBe('LOW');
  });

  it('memberFromItem restores the full API composition (regression: body was dropped)', () => {
    const m = memberFromItem(
      persistedItem({
        target_kind: 'API_CALL',
        datasource_id: null,
        sql_text: null,
        query_type: null,
        transactional: null,
        api_connector_id: 'c-1',
        verb: 'POST',
        request_path: '/v1/things',
        request_headers: { 'X-Trace': '1' },
        query_params: { dryRun: 'true' },
        body_type: 'RAW',
        request_content_type: 'application/json',
        request_body: '{"a":1}',
      }),
    );
    expect(m.composition.rawBody).toBe('{"a":1}');
    expect(m.composition.headers).toEqual([{ key: 'X-Trace', value: '1' }]);
    expect(m.composition.queryParams).toEqual([{ key: 'dryRun', value: 'true' }]);
  });

  it('memberToBody(memberFromItem(item)) round-trips the API composition', () => {
    const item = persistedItem({
      target_kind: 'API_CALL',
      datasource_id: null,
      sql_text: null,
      query_type: null,
      transactional: null,
      api_connector_id: 'c-1',
      operation_id: 'op-1',
      verb: 'POST',
      request_path: '/v1/things',
      request_headers: { 'X-Trace': '1' },
      query_params: { dryRun: 'true' },
      body_type: 'FORM_DATA',
      request_content_type: null,
      request_body: null,
      form_fields: [
        { key: 'doc', type: 'FILE', value: 'AAA=', filename: 'a.txt', content_type: 'text/plain' },
      ],
    });
    expect(memberToBody(memberFromItem(item))).toEqual({
      target_kind: 'API_CALL',
      transactional: false,
      api_connector_id: 'c-1',
      operation_id: 'op-1',
      verb: 'POST',
      request_path: '/v1/things',
      request_headers: { 'X-Trace': '1' },
      query_params: { dryRun: 'true' },
      body_type: 'FORM_DATA',
      request_content_type: null,
      request_body: null,
      form_fields: [
        { key: 'doc', type: 'FILE', value: 'AAA=', filename: 'a.txt', content_type: 'text/plain' },
      ],
      binary_filename: null,
    });
  });
});
