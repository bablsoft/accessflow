import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import {
  adminErrorMessage,
  authErrorMessage,
  reviewErrorMessage,
  reviewPlanErrorMessage,
  setupErrorMessage,
} from '../apiErrors';

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

describe('reviewErrorMessage', () => {
  it('maps 403 FORBIDDEN to the self-approval message', () => {
    expect(reviewErrorMessage(buildAxiosError(403, { error: 'FORBIDDEN' })))
      .toBe('You cannot review a query you submitted yourself.');
  });

  it('maps REVIEWER_NOT_ELIGIBLE', () => {
    expect(reviewErrorMessage(buildAxiosError(403, { error: 'REVIEWER_NOT_ELIGIBLE' })))
      .toBe('You are not an approver at this stage.');
  });

  it('maps QUERY_NOT_PENDING_REVIEW', () => {
    expect(reviewErrorMessage(buildAxiosError(409, { error: 'QUERY_NOT_PENDING_REVIEW' })))
      .toBe('This query is no longer pending review.');
  });

  it('falls back to ProblemDetail.title', () => {
    expect(reviewErrorMessage(buildAxiosError(500, { title: 'Boom' }))).toBe('Boom');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(reviewErrorMessage(undefined))
      .toBe('Could not record the review decision. Please try again.');
  });
});

describe('adminErrorMessage', () => {
  it('maps EMAIL_ALREADY_EXISTS', () => {
    expect(adminErrorMessage(buildAxiosError(409, { error: 'EMAIL_ALREADY_EXISTS' })))
      .toBe('A user with that email already exists.');
  });

  it('maps USER_NOT_FOUND', () => {
    expect(adminErrorMessage(buildAxiosError(404, { error: 'USER_NOT_FOUND' })))
      .toBe('User not found.');
  });

  it('maps ILLEGAL_USER_OPERATION', () => {
    expect(adminErrorMessage(buildAxiosError(422, { error: 'ILLEGAL_USER_OPERATION' })))
      .toBe('This user change is not permitted (admins cannot demote or deactivate themselves).');
  });

  it('maps NOTIFICATION_CHANNEL_CONFIG_INVALID', () => {
    expect(
      adminErrorMessage(buildAxiosError(422, { error: 'NOTIFICATION_CHANNEL_CONFIG_INVALID' })),
    ).toBe('Channel configuration is missing required fields.');
  });

  it('maps NOTIFICATION_CHANNEL_NOT_FOUND', () => {
    expect(
      adminErrorMessage(buildAxiosError(404, { error: 'NOTIFICATION_CHANNEL_NOT_FOUND' })),
    ).toBe('Notification channel not found.');
  });

  it('maps NOTIFICATION_DELIVERY_FAILED', () => {
    expect(
      adminErrorMessage(buildAxiosError(502, { error: 'NOTIFICATION_DELIVERY_FAILED' })),
    ).toBe('Test notification could not be delivered.');
  });

  it('maps BAD_AUDIT_QUERY', () => {
    expect(adminErrorMessage(buildAxiosError(400, { error: 'BAD_AUDIT_QUERY' })))
      .toBe('Audit log filter is invalid (page size or resource type).');
  });

  it('falls back to ProblemDetail.title', () => {
    expect(adminErrorMessage(buildAxiosError(500, { title: 'Boom' }))).toBe('Boom');
  });

  it('falls back to ProblemDetail.detail', () => {
    expect(adminErrorMessage(buildAxiosError(500, { detail: 'why' }))).toBe('why');
  });

  it('falls back to the axios error message', () => {
    const err = new AxiosError('Network Error');
    expect(adminErrorMessage(err)).toBe('Network Error');
  });

  it('handles non-axios errors', () => {
    expect(adminErrorMessage(new Error('boom'))).toBe('boom');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(adminErrorMessage(undefined)).toBe('Could not complete the request. Please try again.');
  });
});

describe('reviewPlanErrorMessage', () => {
  it('maps REVIEW_PLAN_IN_USE', () => {
    expect(reviewPlanErrorMessage(buildAxiosError(409, { error: 'REVIEW_PLAN_IN_USE' })))
      .toBe('This review plan is attached to a datasource and cannot be deleted.');
  });

  it('maps REVIEW_PLAN_NOT_FOUND', () => {
    expect(reviewPlanErrorMessage(buildAxiosError(404, { error: 'REVIEW_PLAN_NOT_FOUND' })))
      .toBe('Review plan not found.');
  });

  it('maps ILLEGAL_REVIEW_PLAN', () => {
    expect(reviewPlanErrorMessage(buildAxiosError(422, { error: 'ILLEGAL_REVIEW_PLAN' })))
      .toBe('That review plan configuration is not allowed.');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(reviewPlanErrorMessage(undefined))
      .toBe('Could not save the review plan. Please try again.');
  });
});
