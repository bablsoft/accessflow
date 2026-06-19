import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import '@/i18n';
import { queryReplayErrorMessage } from './apiErrors';

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
