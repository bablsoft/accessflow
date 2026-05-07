import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import { authErrorMessage, setupErrorMessage } from '../apiErrors';

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

describe('setupErrorMessage', () => {
  it('maps SETUP_ALREADY_COMPLETED to a friendly hint', () => {
    expect(setupErrorMessage(buildAxiosError(409, { error: 'SETUP_ALREADY_COMPLETED' })))
      .toBe('Setup is already complete — please sign in.');
  });

  it('maps EMAIL_ALREADY_EXISTS to a duplicate-email message', () => {
    expect(setupErrorMessage(buildAxiosError(409, { error: 'EMAIL_ALREADY_EXISTS' })))
      .toBe('A user with that email already exists.');
  });

  it('falls back to ProblemDetail.title for unknown errors', () => {
    expect(setupErrorMessage(buildAxiosError(500, { title: 'Server exploded' })))
      .toBe('Server exploded');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(setupErrorMessage(undefined)).toBe('Could not complete setup. Please try again.');
  });
});
