import { describe, expect, it } from 'vitest';
import { resolveRouteGuard } from '../routeGuard';

const NO_USER = null;
const SOME_USER = { id: 'u-1' };

describe('resolveRouteGuard', () => {
  describe('setupRequired === true', () => {
    it('redirects protected paths to /setup', () => {
      expect(
        resolveRouteGuard({
          setupRequired: true,
          user: NO_USER,
          pathname: '/editor',
        }),
      ).toEqual({ type: 'navigate', to: '/setup' });
    });

    it('allows the /setup page itself', () => {
      expect(
        resolveRouteGuard({
          setupRequired: true,
          user: NO_USER,
          pathname: '/setup',
        }),
      ).toEqual({ type: 'allow' });
    });

    it('allows invitation acceptance', () => {
      expect(
        resolveRouteGuard({
          setupRequired: true,
          user: NO_USER,
          pathname: '/invite/abc',
        }),
      ).toEqual({ type: 'allow' });
    });

    it('allows forgot-password', () => {
      expect(
        resolveRouteGuard({
          setupRequired: true,
          user: NO_USER,
          pathname: '/forgot-password',
        }),
      ).toEqual({ type: 'allow' });
    });

    it('allows reset-password by token', () => {
      expect(
        resolveRouteGuard({
          setupRequired: true,
          user: NO_USER,
          pathname: '/reset-password/token-xyz',
        }),
      ).toEqual({ type: 'allow' });
    });
  });

  describe('setupRequired === false', () => {
    it('redirects unauthenticated visitors away from /setup to /login', () => {
      expect(
        resolveRouteGuard({
          setupRequired: false,
          user: NO_USER,
          pathname: '/setup',
        }),
      ).toEqual({ type: 'navigate', to: '/login' });
    });

    it('keeps an authenticated user on /setup so the SMTP step can render', () => {
      // This is the race the e2e setup-wizard spec exercises: after the
      // account step succeeds the store flips setupRequired to false and sets
      // the session in the same render. Without the !user guard the SMTP
      // step would unmount.
      expect(
        resolveRouteGuard({
          setupRequired: false,
          user: SOME_USER,
          pathname: '/setup',
        }),
      ).toEqual({ type: 'allow' });
    });

    it('allows non-/setup paths regardless of auth', () => {
      expect(
        resolveRouteGuard({
          setupRequired: false,
          user: NO_USER,
          pathname: '/login',
        }),
      ).toEqual({ type: 'allow' });
      expect(
        resolveRouteGuard({
          setupRequired: false,
          user: SOME_USER,
          pathname: '/editor',
        }),
      ).toEqual({ type: 'allow' });
    });
  });

  describe('setupRequired === null (BootGate still resolving)', () => {
    it('does not redirect anywhere', () => {
      expect(
        resolveRouteGuard({
          setupRequired: null,
          user: NO_USER,
          pathname: '/editor',
        }),
      ).toEqual({ type: 'allow' });
      expect(
        resolveRouteGuard({
          setupRequired: null,
          user: NO_USER,
          pathname: '/setup',
        }),
      ).toEqual({ type: 'allow' });
    });
  });
});
