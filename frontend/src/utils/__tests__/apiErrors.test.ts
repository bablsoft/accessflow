import { describe, expect, it } from 'vitest';
import { AxiosError, type AxiosResponse } from 'axios';
import {
  adminErrorMessage,
  apiErrorTraceId,
  authErrorMessage,
  datasourceCreateErrorMessage,
  datasourceGrantErrorMessage,
  isTotpInvalidError,
  isTotpRequiredError,
  profileErrorMessage,
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

  it('maps AI_CONFIG_NAME_ALREADY_EXISTS', () => {
    expect(adminErrorMessage(buildAxiosError(409, { error: 'AI_CONFIG_NAME_ALREADY_EXISTS' })))
      .toBe('An AI configuration with that name already exists. Pick a different name.');
  });

  it('maps AI_CONFIG_IN_USE', () => {
    expect(adminErrorMessage(buildAxiosError(409, { error: 'AI_CONFIG_IN_USE' })))
      .toBe('This AI configuration is bound to one or more datasources and cannot be deleted.');
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

describe('datasourceGrantErrorMessage', () => {
  it('maps DATASOURCE_PERMISSION_ALREADY_EXISTS', () => {
    expect(
      datasourceGrantErrorMessage(
        buildAxiosError(409, { error: 'DATASOURCE_PERMISSION_ALREADY_EXISTS' }),
      ),
    ).toBe('This user already has a permission row for this datasource.');
  });

  it('maps ILLEGAL_DATASOURCE_PERMISSION', () => {
    expect(
      datasourceGrantErrorMessage(
        buildAxiosError(422, { error: 'ILLEGAL_DATASOURCE_PERMISSION' }),
      ),
    ).toBe('That user is not part of your organization.');
  });

  it('falls back to ProblemDetail.title', () => {
    expect(datasourceGrantErrorMessage(buildAxiosError(500, { title: 'Boom' }))).toBe('Boom');
  });

  it('falls back to ProblemDetail.detail', () => {
    expect(datasourceGrantErrorMessage(buildAxiosError(500, { detail: 'why' }))).toBe('why');
  });

  it('falls back to the axios error message', () => {
    const err = new AxiosError('Network Error');
    expect(datasourceGrantErrorMessage(err)).toBe('Network Error');
  });

  it('handles non-axios errors', () => {
    expect(datasourceGrantErrorMessage(new Error('boom'))).toBe('boom');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(datasourceGrantErrorMessage(undefined))
      .toBe('Failed to grant access. Please try again.');
  });
});

describe('isTotpRequiredError / isTotpInvalidError', () => {
  it('detects TOTP_REQUIRED', () => {
    expect(isTotpRequiredError(buildAxiosError(401, { error: 'TOTP_REQUIRED' }))).toBe(true);
    expect(isTotpRequiredError(buildAxiosError(401, { error: 'OTHER' }))).toBe(false);
    expect(isTotpRequiredError(new Error('not axios'))).toBe(false);
  });

  it('detects TOTP_INVALID', () => {
    expect(isTotpInvalidError(buildAxiosError(401, { error: 'TOTP_INVALID' }))).toBe(true);
    expect(isTotpInvalidError(buildAxiosError(401, { error: 'OTHER' }))).toBe(false);
    expect(isTotpInvalidError(new Error('not axios'))).toBe(false);
  });
});

describe('authErrorMessage TOTP branches', () => {
  it('maps TOTP_REQUIRED', () => {
    expect(authErrorMessage(buildAxiosError(401, { error: 'TOTP_REQUIRED' })))
      .toBe('Enter the code from your authenticator app to continue.');
  });

  it('maps TOTP_INVALID', () => {
    expect(authErrorMessage(buildAxiosError(401, { error: 'TOTP_INVALID' })))
      .toBe('That verification code is not valid. Please try again.');
  });
});

describe('profileErrorMessage', () => {
  it('maps PASSWORD_INCORRECT', () => {
    expect(profileErrorMessage(buildAxiosError(422, { error: 'PASSWORD_INCORRECT' })))
      .toBe('The current password is incorrect.');
  });

  it('maps PASSWORD_CHANGE_NOT_ALLOWED', () => {
    expect(profileErrorMessage(buildAxiosError(422, { error: 'PASSWORD_CHANGE_NOT_ALLOWED' })))
      .toBe('Password change is not allowed for this account.');
  });

  it('maps TOTP_NOT_ENABLED, TOTP_ALREADY_ENABLED, TOTP_INVALID_CODE', () => {
    expect(profileErrorMessage(buildAxiosError(422, { error: 'TOTP_NOT_ENABLED' })))
      .toBe('Two-factor authentication is not enabled.');
    expect(profileErrorMessage(buildAxiosError(422, { error: 'TOTP_ALREADY_ENABLED' })))
      .toBe('Two-factor authentication is already enabled.');
    expect(profileErrorMessage(buildAxiosError(422, { error: 'TOTP_INVALID_CODE' })))
      .toBe('That verification code is not valid.');
  });

  it('falls back to ProblemDetail.title or .detail or message', () => {
    expect(profileErrorMessage(buildAxiosError(500, { title: 'T' }))).toBe('T');
    expect(profileErrorMessage(buildAxiosError(500, { detail: 'D' }))).toBe('D');
    expect(profileErrorMessage(new AxiosError('Net'))).toBe('Net');
  });

  it('handles non-axios errors', () => {
    expect(profileErrorMessage(new Error('boom'))).toBe('boom');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(profileErrorMessage(undefined)).toBe('Could not update your profile. Please try again.');
  });
});

describe('datasourceCreateErrorMessage', () => {
  it('maps CACHE_NOT_WRITABLE with admin-actionable copy mentioning ACCESSFLOW_DRIVER_CACHE', () => {
    const msg = datasourceCreateErrorMessage(buildAxiosError(422, {
      error: 'DATASOURCE_DRIVER_UNAVAILABLE',
      reason: 'CACHE_NOT_WRITABLE',
      dbType: 'MYSQL',
      detail: 'path: /var/lib/accessflow/drivers',
    }));
    expect(msg).toContain('ACCESSFLOW_DRIVER_CACHE');
    expect(msg).toContain('path: /var/lib/accessflow/drivers');
  });

  it('maps OFFLINE_CACHE_MISS with hint about offline mode', () => {
    const msg = datasourceCreateErrorMessage(buildAxiosError(422, {
      error: 'DATASOURCE_DRIVER_UNAVAILABLE',
      reason: 'OFFLINE_CACHE_MISS',
      dbType: 'MYSQL',
      detail: 'expected file: mysql-connector-j-9.7.0.jar',
    }));
    expect(msg).toContain('ACCESSFLOW_DRIVERS_OFFLINE');
    expect(msg).toContain('MYSQL');
  });

  it('maps DOWNLOAD_FAILED with hint about Maven mirror', () => {
    const msg = datasourceCreateErrorMessage(buildAxiosError(422, {
      error: 'DATASOURCE_DRIVER_UNAVAILABLE',
      reason: 'DOWNLOAD_FAILED',
      dbType: 'ORACLE',
      detail: 'HTTP status 403',
    }));
    expect(msg).toContain('ACCESSFLOW_DRIVERS_REPOSITORY_URL');
    expect(msg).toContain('ORACLE');
  });

  it('maps CHECKSUM_MISMATCH with integrity message', () => {
    const msg = datasourceCreateErrorMessage(buildAxiosError(422, {
      error: 'DATASOURCE_DRIVER_UNAVAILABLE',
      reason: 'CHECKSUM_MISMATCH',
      dbType: 'MSSQL',
      detail: 'expected abc got def',
    }));
    expect(msg).toContain('integrity');
    expect(msg).toContain('MSSQL');
  });

  it('falls back to UNAVAILABLE for unknown reasons', () => {
    const msg = datasourceCreateErrorMessage(buildAxiosError(422, {
      error: 'DATASOURCE_DRIVER_UNAVAILABLE',
      reason: 'UNAVAILABLE',
      dbType: 'MARIADB',
      detail: 'unknown',
    }));
    expect(msg).toContain('MARIADB');
    expect(msg).toContain('cannot be loaded');
  });

  it('returns null for unrelated errors so caller can fall through', () => {
    expect(datasourceCreateErrorMessage(undefined)).toBeNull();
  });

  it('maps DATASOURCE_NAME_ALREADY_EXISTS to a friendly duplicate message', () => {
    expect(datasourceCreateErrorMessage(buildAxiosError(409, {
      error: 'DATASOURCE_NAME_ALREADY_EXISTS',
      title: 'Conflict',
      detail: 'A datasource with that name already exists',
    }))).toBe(
      'A datasource with that name already exists in your organization. Pick a different name.',
    );
  });

  it('prefers ProblemDetail.detail over .title for non-driver 4xx fallbacks', () => {
    expect(datasourceCreateErrorMessage(buildAxiosError(409, {
      title: 'Conflict',
      detail: 'Something more specific',
    }))).toBe('Something more specific');
  });
});

describe('apiErrorTraceId', () => {
  it('returns the traceId from a ProblemDetail body', () => {
    expect(apiErrorTraceId(buildAxiosError(500, { traceId: 'abc-123' }))).toBe('abc-123');
  });

  it('returns undefined when the body has no traceId', () => {
    expect(apiErrorTraceId(buildAxiosError(500, { title: 'Boom' }))).toBeUndefined();
  });

  it('returns undefined when response is missing', () => {
    expect(apiErrorTraceId(new AxiosError('Network error'))).toBeUndefined();
  });

  it('returns undefined for non-axios errors', () => {
    expect(apiErrorTraceId(new Error('boom'))).toBeUndefined();
    expect(apiErrorTraceId(undefined)).toBeUndefined();
    expect(apiErrorTraceId('weird')).toBeUndefined();
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

  it('maps REVIEW_PLAN_NAME_ALREADY_EXISTS', () => {
    expect(
      reviewPlanErrorMessage(buildAxiosError(409, { error: 'REVIEW_PLAN_NAME_ALREADY_EXISTS' })),
    ).toBe('A review plan with that name already exists. Pick a different name.');
  });

  it('returns a generic fallback for unknown values', () => {
    expect(reviewPlanErrorMessage(undefined))
      .toBe('Could not save the review plan. Please try again.');
  });
});
