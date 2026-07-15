import { describe, expect, it, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { PERMISSIONS, hasAnyPermission, hasPermission, usePermission } from './permissions';
import { useAuthStore } from '@/store/authStore';
import type { AuthUser } from '@/api/auth';

function user(permissions: string[]): Pick<AuthUser, 'permissions'> {
  return { permissions };
}

describe('permissions', () => {
  it('exposes the full 38-entry catalog mirror', () => {
    expect(PERMISSIONS).toHaveLength(38);
    expect(new Set(PERMISSIONS).size).toBe(PERMISSIONS.length);
  });

  describe('hasPermission', () => {
    it('returns true when the user holds the permission', () => {
      expect(hasPermission(user(['QUERY_REVIEW']), 'QUERY_REVIEW')).toBe(true);
    });

    it('returns false when the user does not hold the permission', () => {
      expect(hasPermission(user(['QUERY_REVIEW']), 'USER_MANAGE')).toBe(false);
    });

    it('returns false for a null or undefined user', () => {
      expect(hasPermission(null, 'QUERY_REVIEW')).toBe(false);
      expect(hasPermission(undefined, 'QUERY_REVIEW')).toBe(false);
    });

    it('returns false when the permissions array is missing', () => {
      expect(
        hasPermission({ permissions: undefined as unknown as string[] }, 'QUERY_REVIEW'),
      ).toBe(false);
    });
  });

  describe('hasAnyPermission', () => {
    it('returns true when the user holds at least one of the permissions', () => {
      expect(
        hasAnyPermission(user(['AI_MANAGE']), ['USER_MANAGE', 'AI_MANAGE']),
      ).toBe(true);
    });

    it('returns false when the user holds none of the permissions', () => {
      expect(hasAnyPermission(user(['AI_MANAGE']), ['USER_MANAGE', 'ROLE_MANAGE'])).toBe(false);
    });

    it('returns false for an empty permission list', () => {
      expect(hasAnyPermission(user(['AI_MANAGE']), [])).toBe(false);
    });

    it('returns false for a null user', () => {
      expect(hasAnyPermission(null, ['AI_MANAGE'])).toBe(false);
    });
  });

  describe('usePermission', () => {
    beforeEach(() => {
      useAuthStore.setState({ user: null, accessToken: null });
    });

    it('reads the signed-in user from the auth store', () => {
      useAuthStore.setState({
        user: {
          id: 'u-1',
          email: 'a@b.c',
          display_name: 'A',
          role: 'ADMIN',
          role_id: null,
          permissions: ['ROLE_MANAGE'],
          auth_provider: 'LOCAL',
          totp_enabled: false,
          platform_admin: false,
          preferred_language: null,
        },
        accessToken: 'token',
      });
      const { result } = renderHook(() => usePermission('ROLE_MANAGE'));
      expect(result.current).toBe(true);
    });

    it('returns false when no user is signed in', () => {
      const { result } = renderHook(() => usePermission('ROLE_MANAGE'));
      expect(result.current).toBe(false);
    });
  });
});
