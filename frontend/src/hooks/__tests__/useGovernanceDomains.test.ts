import { afterEach, describe, expect, it } from 'vitest';
import { renderHook } from '@testing-library/react';
import type { AuthUser } from '@/api/auth';
import { useAuthStore } from '@/store/authStore';
import { governanceDomainsOf, useGovernanceDomains } from '../useGovernanceDomains';

function user(overrides: Partial<AuthUser> = {}): AuthUser {
  return {
    id: 'u-1',
    email: 'u@example.com',
    display_name: 'U',
    role: 'ADMIN',
    role_id: null,
    permissions: [],
    auth_provider: 'LOCAL',
    totp_enabled: false,
    platform_admin: false,
    preferred_language: null,
    ...overrides,
  };
}

afterEach(() => {
  useAuthStore.setState({ user: null, accessToken: null });
});

describe('governanceDomainsOf (#926)', () => {
  it('reads both flags off the signed-in user', () => {
    expect(governanceDomainsOf(user({ governs_apis: true, governs_deployments: false })))
      .toEqual({ apis: true, deployments: false });
    expect(governanceDomainsOf(user({ governs_apis: false, governs_deployments: true })))
      .toEqual({ apis: false, deployments: true });
  });

  it('fails open when a flag is absent — a session issued before #926', () => {
    expect(governanceDomainsOf(user())).toEqual({ apis: true, deployments: true });
    expect(governanceDomainsOf(user({ governs_apis: false })))
      .toEqual({ apis: false, deployments: true });
  });

  it('fails open when nobody is signed in', () => {
    expect(governanceDomainsOf(null)).toEqual({ apis: true, deployments: true });
    expect(governanceDomainsOf(undefined)).toEqual({ apis: true, deployments: true });
  });
});

describe('useGovernanceDomains (#926)', () => {
  it('reads the flags from the auth store', () => {
    useAuthStore.setState({
      user: user({ governs_apis: false, governs_deployments: true }),
      accessToken: 't',
    });
    const { result } = renderHook(() => useGovernanceDomains());
    expect(result.current).toEqual({ apis: false, deployments: true });
  });

  it('defaults both domains on with no session', () => {
    const { result } = renderHook(() => useGovernanceDomains());
    expect(result.current).toEqual({ apis: true, deployments: true });
  });
});
