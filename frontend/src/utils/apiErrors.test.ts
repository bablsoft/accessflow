import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import '@/i18n';
import { adminErrorMessage, queryReplayErrorMessage, rolesErrorMessage } from './apiErrors';

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
