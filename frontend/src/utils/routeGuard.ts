// Top-of-router redirect rules used by App.tsx. Pure function so the routing
// decisions are unit-testable without mounting the lazy-loaded page tree.
//
// Two states drive the guard:
//   - setupRequired: null while BootGate is still resolving setup-status,
//     true when no admin exists, false once setup is complete.
//   - user: the authenticated session, or null if no one is signed in.
//
// During the wizard the account step flips setupRequired to false AND sets the
// session in the same React render. The `!user` guard on the /setup → /login
// branch is what keeps the SMTP step from being unmounted mid-flow.

export interface RouteGuardInput {
  setupRequired: boolean | null;
  user: { id: string } | null;
  pathname: string;
}

export type RouteGuardDecision =
  | { type: 'navigate'; to: '/setup' | '/login' }
  | { type: 'allow' };

const SETUP_BYPASS_PATHS: ReadonlyArray<string | RegExp> = [
  '/setup',
  /^\/invite\//,
  '/forgot-password',
  /^\/reset-password\//,
];

function isSetupBypass(pathname: string): boolean {
  return SETUP_BYPASS_PATHS.some((entry) =>
    typeof entry === 'string' ? entry === pathname : entry.test(pathname),
  );
}

export function resolveRouteGuard({
  setupRequired,
  user,
  pathname,
}: RouteGuardInput): RouteGuardDecision {
  if (setupRequired === true && !isSetupBypass(pathname)) {
    return { type: 'navigate', to: '/setup' };
  }
  if (setupRequired === false && !user && pathname === '/setup') {
    return { type: 'navigate', to: '/login' };
  }
  return { type: 'allow' };
}
