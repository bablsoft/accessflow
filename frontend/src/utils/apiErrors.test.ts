import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import '@/i18n';
import {
  adminErrorMessage,
  apiQuotaExceededBody,
  apiQuotaWarning,
  apiRequestExecuteErrorMessage,
  auditSinkErrorMessage,
  decisionHookErrorMessage,
  queryReplayErrorMessage,
  apiReplayErrorMessage,
  rolesErrorMessage,
  serviceAccountErrorMessage,
} from './apiErrors';

function axiosError(status: number, data: unknown): AxiosError {
  const response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: {} as never,
  } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
}

describe('queryReplayErrorMessage', () => {
  it('maps QUERY_SNAPSHOT_NOT_FOUND to a friendly message', () => {
    const msg = queryReplayErrorMessage(axiosError(404, { error: 'QUERY_SNAPSHOT_NOT_FOUND' }));
    expect(msg).toMatch(/only executed queries can be replayed/i);
  });

  it('renders missing tables when REPLAY_SCHEMA_INCOMPATIBLE carries them', () => {
    const msg = queryReplayErrorMessage(
      axiosError(422, {
        error: 'REPLAY_SCHEMA_INCOMPATIBLE',
        missing_tables: ['public.users', 'public.orders'],
      }),
    );
    expect(msg).toContain('public.users, public.orders');
  });

  it('falls back to detail for REPLAY_SCHEMA_INCOMPATIBLE without missing tables', () => {
    const msg = queryReplayErrorMessage(
      axiosError(422, { error: 'REPLAY_SCHEMA_INCOMPATIBLE', detail: 'different engine' }),
    );
    expect(msg).toBe('different engine');
  });

  it('uses the generic incompatible message when no detail or tables present', () => {
    const msg = queryReplayErrorMessage(axiosError(422, { error: 'REPLAY_SCHEMA_INCOMPATIBLE' }));
    expect(msg).toMatch(/not compatible/i);
  });

  it('returns a generic message for non-axios errors', () => {
    const msg = queryReplayErrorMessage(new Error('boom'));
    expect(msg).toBe('boom');
  });
});

describe('auditSinkErrorMessage (#628)', () => {
  it('maps AUDIT_SINK_NAME_EXISTS to a friendly message', () => {
    const msg = auditSinkErrorMessage(axiosError(409, { error: 'AUDIT_SINK_NAME_EXISTS' }));
    expect(msg).toMatch(/name/i);
  });

  it('maps AUDIT_SINK_NOT_FOUND to a friendly message', () => {
    const msg = auditSinkErrorMessage(axiosError(404, { error: 'AUDIT_SINK_NOT_FOUND' }));
    expect(msg).toMatch(/not found/i);
  });

  it('prefers the backend-localised detail for AUDIT_SINK_CONFIG_INVALID', () => {
    const msg = auditSinkErrorMessage(
      axiosError(422, {
        error: 'AUDIT_SINK_CONFIG_INVALID',
        title: 'Unprocessable Content',
        detail: 'Missing required config key: token',
      }),
    );
    expect(msg).toBe('Missing required config key: token');
  });

  it('falls back to a friendly message when the 422 carries no detail', () => {
    const msg = auditSinkErrorMessage(
      axiosError(422, { error: 'AUDIT_SINK_CONFIG_INVALID', title: 'Unprocessable Content' }),
    );
    expect(msg).toMatch(/configuration/i);
  });

  it('prefers the backend detail for AUDIT_SINK_TEST_FAILED', () => {
    const msg = auditSinkErrorMessage(
      axiosError(502, {
        error: 'AUDIT_SINK_TEST_FAILED',
        title: 'Bad Gateway',
        detail: 'Splunk HEC returned 403',
      }),
    );
    expect(msg).toBe('Splunk HEC returned 403');
  });

  it('falls back to a friendly message when the 502 carries no detail', () => {
    const msg = auditSinkErrorMessage(
      axiosError(502, { error: 'AUDIT_SINK_TEST_FAILED', title: 'Bad Gateway' }),
    );
    expect(msg).toMatch(/test/i);
  });

  it('falls through to detail, title, and generic fallback', () => {
    expect(auditSinkErrorMessage(axiosError(500, { detail: 'boom detail' }))).toBe('boom detail');
    expect(auditSinkErrorMessage(axiosError(500, { title: 'Server Error' }))).toBe('Server Error');
    expect(auditSinkErrorMessage(new Error('plain boom'))).toBe('plain boom');
  });
});

describe('adminErrorMessage — INVALID_ERASURE_CONFIG', () => {
  it('prefers the backend-localised detail over the ProblemDetail title', () => {
    const msg = adminErrorMessage(
      axiosError(422, {
        error: 'INVALID_ERASURE_CONFIG',
        title: 'Unprocessable Content',
        detail: 'A target table is required when using conditions or a raw WHERE clause',
      }),
    );
    expect(msg).toBe('A target table is required when using conditions or a raw WHERE clause');
  });

  it('falls back to a friendly message when the 422 carries no detail', () => {
    const msg = adminErrorMessage(
      axiosError(422, { error: 'INVALID_ERASURE_CONFIG', title: 'Unprocessable Content' }),
    );
    expect(msg).toMatch(/erasure request configuration is invalid/i);
  });
});

describe('rolesErrorMessage (AF-522)', () => {
  it('maps ROLE_IN_USE to a friendly message', () => {
    const msg = rolesErrorMessage(axiosError(409, { error: 'ROLE_IN_USE' }));
    expect(msg).toMatch(/assigned to users/i);
  });

  it('maps ROLE_SYSTEM_IMMUTABLE to a friendly message', () => {
    const msg = rolesErrorMessage(axiosError(409, { error: 'ROLE_SYSTEM_IMMUTABLE' }));
    expect(msg).toMatch(/system role/i);
  });

  it('maps ROLE_NAME_ALREADY_EXISTS to a friendly message', () => {
    const msg = rolesErrorMessage(axiosError(409, { error: 'ROLE_NAME_ALREADY_EXISTS' }));
    expect(msg).toMatch(/name/i);
  });

  it('maps ROLE_NOT_FOUND to a friendly message', () => {
    const msg = rolesErrorMessage(axiosError(404, { error: 'ROLE_NOT_FOUND' }));
    expect(msg).toMatch(/no longer exists/i);
  });

  it('prefers the backend detail for unmapped codes', () => {
    const msg = rolesErrorMessage(axiosError(400, { error: 'OTHER', detail: 'specific detail' }));
    expect(msg).toBe('specific detail');
  });

  it('falls back to the generic message for unknown errors', () => {
    const msg = rolesErrorMessage({});
    expect(msg).toMatch(/role/i);
  });
});

describe('serviceAccountErrorMessage (#875)', () => {
  it('interpolates the declared field into the bootstrap-managed message', () => {
    const msg = serviceAccountErrorMessage(
      axiosError(409, { error: 'SERVICE_ACCOUNT_BOOTSTRAP_MANAGED', field: 'display_name' }),
    );
    expect(msg).toContain('display_name');
    expect(msg).toMatch(/bootstrap/i);
  });

  it('interpolates the offending tool into the unknown-tool message', () => {
    const msg = serviceAccountErrorMessage(
      axiosError(422, { error: 'SERVICE_ACCOUNT_UNKNOWN_MCP_TOOL', tool: 'drop_everything' }),
    );
    expect(msg).toContain('drop_everything');
  });

  it('maps the code table to friendly messages', () => {
    expect(
      serviceAccountErrorMessage(axiosError(409, { error: 'SERVICE_ACCOUNT_KEY_BOOTSTRAP_DECLARED' })),
    ).toMatch(/bootstrap/i);
    expect(serviceAccountErrorMessage(axiosError(409, { error: 'EMAIL_ALREADY_EXISTS' }))).toMatch(
      /already exists/i,
    );
    expect(
      serviceAccountErrorMessage(axiosError(422, { error: 'SERVICE_ACCOUNT_OWNER_INVALID' })),
    ).toMatch(/owner/i);
  });

  it('prefers the backend detail, then the title, for unmapped codes', () => {
    expect(serviceAccountErrorMessage(axiosError(400, { error: 'OTHER', detail: 'specific' }))).toBe(
      'specific',
    );
    expect(serviceAccountErrorMessage(axiosError(400, { error: 'OTHER', title: 'Bad Request' }))).toBe(
      'Bad Request',
    );
  });

  it('falls back to the generic message for unknown errors', () => {
    expect(serviceAccountErrorMessage({})).toMatch(/service account/i);
    expect(serviceAccountErrorMessage(new Error('boom'))).toBe('boom');
  });
});

describe('decisionHookErrorMessage (#945)', () => {
  it('maps DECISION_HOOK_NOT_FOUND to a friendly message', () => {
    expect(decisionHookErrorMessage(axiosError(404, { error: 'DECISION_HOOK_NOT_FOUND' }))).toMatch(
      /decision hook/i,
    );
  });

  it('prefers the localized detail for a scope conflict and an invalid URL', () => {
    expect(
      decisionHookErrorMessage(
        axiosError(409, { error: 'DECISION_HOOK_SCOPE_CONFLICT', detail: 'default taken' }),
      ),
    ).toBe('default taken');
    expect(
      decisionHookErrorMessage(axiosError(422, { error: 'DECISION_HOOK_INVALID', detail: 'https only' })),
    ).toBe('https only');
  });

  it('falls back to its own messages without a detail', () => {
    expect(decisionHookErrorMessage(axiosError(409, { error: 'DECISION_HOOK_SCOPE_CONFLICT' }))).toMatch(
      /already/i,
    );
    expect(decisionHookErrorMessage(axiosError(422, { error: 'DECISION_HOOK_INVALID' }))).toMatch(
      /endpoint/i,
    );
  });

  it('uses detail, then title, for unmapped codes', () => {
    expect(decisionHookErrorMessage(axiosError(400, { error: 'X', detail: 'specific' }))).toBe('specific');
    expect(decisionHookErrorMessage(axiosError(400, { error: 'X', title: 'Bad Request' }))).toBe(
      'Bad Request',
    );
  });

  it('falls back to the generic message for unknown errors', () => {
    expect(decisionHookErrorMessage({})).toMatch(/decision hook/i);
    expect(decisionHookErrorMessage(new Error('boom'))).toBe('boom');
  });
});

describe('apiReplayErrorMessage', () => {
  it('maps API_REPLAY_OPERATION_UNAVAILABLE to a friendly message', () => {
    const msg = apiReplayErrorMessage(axiosError(422, { error: 'API_REPLAY_OPERATION_UNAVAILABLE' }));
    expect(msg).toMatch(/no longer in the connector's catalog/i);
  });

  it('maps API_REQUEST_INVALID_STATE to a friendly message', () => {
    const msg = apiReplayErrorMessage(axiosError(409, { error: 'API_REQUEST_INVALID_STATE' }));
    expect(msg).toMatch(/only finished requests/i);
  });

  it('maps API_REQUEST_PERMISSION_DENIED to a friendly message', () => {
    const msg = apiReplayErrorMessage(axiosError(403, { error: 'API_REQUEST_PERMISSION_DENIED' }));
    expect(msg).toMatch(/no longer have permission/i);
  });

  it('passes the server detail through for other codes', () => {
    const msg = apiReplayErrorMessage(
      axiosError(422, { error: 'API_REQUEST_VALIDATION_ERROR', detail: 'path is too long' }),
    );
    expect(msg).toBe('path is too long');
  });

  it('falls back to the title, then the axios message', () => {
    expect(apiReplayErrorMessage(axiosError(500, { title: 'Server error' }))).toBe('Server error');
    expect(apiReplayErrorMessage(axiosError(500, {}))).toBe('Request failed');
  });

  it('uses the error message, then the generic message, for non-axios errors', () => {
    expect(apiReplayErrorMessage(new Error('boom'))).toBe('boom');
    expect(apiReplayErrorMessage('nope')).toMatch(/could not replay the api request/i);
  });
});

describe('apiQuotaExceededBody / apiRequestExecuteErrorMessage (#974)', () => {
  const quota = {
    error: 'API_CONNECTOR_QUOTA_EXCEEDED',
    detail: 'Connector rate limit reached — queued',
    limitKind: 'MINUTE',
    retryAfterSeconds: 42,
    throttledUntil: '2026-11-10T10:01:00Z',
    queuedForRetry: true,
  };

  it('returns the body only for the quota code', () => {
    expect(apiQuotaExceededBody(axiosError(429, quota))?.limitKind).toBe('MINUTE');
    expect(apiQuotaExceededBody(axiosError(409, { error: 'OTHER' }))).toBeNull();
    expect(apiQuotaExceededBody(new Error('boom'))).toBeNull();
  });

  it('prefers the server detail on a 429 and falls back to the queued text', () => {
    expect(apiRequestExecuteErrorMessage(axiosError(429, quota), () => 'x')).toBe(
      'Connector rate limit reached — queued',
    );
    const { detail: _detail, ...noDetail } = quota;
    expect(apiRequestExecuteErrorMessage(axiosError(429, noDetail), () => 'x')).toMatch(/queued/i);
  });

  it('routes other errors through the generic extractor', () => {
    expect(
      apiRequestExecuteErrorMessage(axiosError(500, { detail: 'Upstream down' }), () => 'x'),
    ).toBe('Upstream down');
    expect(apiRequestExecuteErrorMessage(null, () => 'fallback')).toBe('fallback');
  });
});

describe('apiQuotaWarning (#974)', () => {
  const label = (k: string) => `kind:${k}`;
  it('appends the limit kind to the server detail', () => {
    const err = axiosError(429, { error: 'API_CONNECTOR_QUOTA_EXCEEDED', detail: 'Queued', limitKind: 'DAY' });
    expect(apiQuotaWarning(err, label)).toBe('Queued (kind:DAY)');
  });
  it('returns the bare text without a limit kind, and null for other errors', () => {
    expect(apiQuotaWarning(axiosError(429, { error: 'API_CONNECTOR_QUOTA_EXCEEDED', detail: 'Queued' }), label)).toBe('Queued');
    expect(apiQuotaWarning(axiosError(500, { detail: 'x' }), label)).toBeNull();
  });
});
