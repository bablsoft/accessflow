import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import '@/i18n';
import {
  adminErrorMessage,
  auditSinkErrorMessage,
  queryReplayErrorMessage,
  rolesErrorMessage,
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
