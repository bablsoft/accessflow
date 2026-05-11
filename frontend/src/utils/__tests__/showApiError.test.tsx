import { describe, expect, it, vi } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import type { MessageInstance } from 'antd/es/message/interface';
import { showApiError } from '../showApiError';

const buildAxiosError = (status: number, data: unknown): AxiosError => {
  const response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: {} as never,
  } as AxiosResponse;
  return new AxiosError('Request failed', undefined, undefined, undefined, response);
};

function fakeMessageApi(): MessageInstance {
  // Only `error` is used; cast through unknown to satisfy the full MessageInstance shape.
  return { error: vi.fn() } as unknown as MessageInstance;
}

describe('showApiError', () => {
  it('passes a plain string when no trace id is present', () => {
    const api = fakeMessageApi();
    const err = buildAxiosError(500, { detail: 'Boom' });

    showApiError(api, err, () => 'Could not save');

    expect(api.error).toHaveBeenCalledTimes(1);
    expect(api.error).toHaveBeenCalledWith('Could not save');
  });

  it('passes a ReactNode with the trace id when one is present', () => {
    const api = fakeMessageApi();
    const err = buildAxiosError(500, { traceId: 'trace-xyz', detail: 'Boom' });

    showApiError(api, err, () => 'Could not save');

    expect(api.error).toHaveBeenCalledTimes(1);
    const calls = (api.error as unknown as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls.length).toBe(1);
    const arg = calls[0]![0] as { duration: number; content: unknown };
    expect(arg.duration).toBe(8);
    expect(arg.content).toBeDefined();
  });

  it('uses the builder function for the error text', () => {
    const api = fakeMessageApi();
    const err = new Error('boom');
    const builder = vi.fn().mockReturnValue('Translated message');

    showApiError(api, err, builder);

    expect(builder).toHaveBeenCalledWith(err);
    expect(api.error).toHaveBeenCalledWith('Translated message');
  });
});
