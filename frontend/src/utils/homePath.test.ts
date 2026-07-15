import { describe, expect, it } from 'vitest';
import { homePathForUser } from './homePath';

function user(permissions: string[]) {
  return { permissions };
}

describe('homePathForUser', () => {
  it('sends an auditor-shaped user (compliance view, no query submit) to the auditor dashboard', () => {
    expect(user(['COMPLIANCE_REPORT_VIEW', 'AUDIT_LOG_VIEW'])).toBeTruthy();
    expect(homePathForUser(user(['COMPLIANCE_REPORT_VIEW', 'AUDIT_LOG_VIEW']))).toBe(
      '/admin/auditor',
    );
  });

  it('sends a user with query submit and compliance view to the personalized dashboard', () => {
    expect(homePathForUser(user(['QUERY_SUBMIT_SELECT', 'COMPLIANCE_REPORT_VIEW']))).toBe(
      '/dashboard',
    );
  });

  it('sends a plain analyst-shaped user to the personalized dashboard', () => {
    expect(homePathForUser(user(['QUERY_SUBMIT_SELECT', 'QUERY_SUBMIT_DML']))).toBe('/dashboard');
  });

  it('sends a user with neither permission to the personalized dashboard', () => {
    expect(homePathForUser(user([]))).toBe('/dashboard');
  });

  it('defaults to the dashboard for a missing user', () => {
    expect(homePathForUser(null)).toBe('/dashboard');
    expect(homePathForUser(undefined)).toBe('/dashboard');
  });
});
