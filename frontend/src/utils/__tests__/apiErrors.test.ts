import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import { authErrorMessage } from '../apiErrors';

const buildAxiosError = (status: number, data: unknown): AxiosError => {
  const response = {
    data,
    status,
    statusText: '',
    headers: {},
    config: {} as never,
  } as AxiosResponse;
  const err = new AxiosError('Request failed', undefined, undefined, undefined, response);
  return err;
};

describe('authErrorMessage', () => {
  it('returns the canonical message on 401', () => {
    expect(authErrorMessage(buildAxiosError(401, { title: 'whatever' })))
      .toBe('Invalid email or password.');
  });

  it('uses ProblemDetail.title when present and not 401', () => {
    expect(authErrorMessage(buildAxiosError(500, { title: 'Server exploded' })))
      .toBe('Server exploded');
  });

  it('falls back to detail when title is missing', () => {
    expect(authErrorMessage(buildAxiosError(500, { detail: 'Boom' })))
      .toBe('Boom');
  });

  it('falls back to the axios error message', () => {
    const err = new AxiosError('Network Error');
    expect(authErrorMessage(err)).toBe('Network Error');
  });

  it('handles non-axios errors', () => {
    expect(authErrorMessage(new Error('boom'))).toBe('boom');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(authErrorMessage(undefined)).toBe('Sign in failed. Please try again.');
    expect(authErrorMessage('weird')).toBe('Sign in failed. Please try again.');
  });
});
