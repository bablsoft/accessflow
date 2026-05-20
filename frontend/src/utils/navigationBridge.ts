import type { NavigateFunction } from 'react-router-dom';

// Module-level handle to React Router's navigate function so non-React code
// (Axios interceptor, etc.) can drive SPA-level redirects without a full page
// reload — keeps AntD message portals and other persistent UI mounted across
// the navigation.
// Set by <NavigationBridgeBinder /> inside the Router; cleared on unmount.
let navigate: NavigateFunction | null = null;

export function setNavigate(fn: NavigateFunction | null): void {
  navigate = fn;
}

export function getNavigate(): NavigateFunction | null {
  return navigate;
}
