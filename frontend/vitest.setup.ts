import '@testing-library/jest-dom/vitest';
import './src/i18n';

// jsdom does not implement window.matchMedia, which Ant Design's responsive
// observers (used by Steps, Grid, etc.) call during render.
if (typeof window !== 'undefined' && !window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

// jsdom does not implement ResizeObserver, used by Ant Design's Dropdown,
// List virtualization, and a few other rc-component primitives.
if (typeof globalThis.ResizeObserver === 'undefined') {
  class ResizeObserverPolyfill {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  (globalThis as unknown as { ResizeObserver: typeof ResizeObserverPolyfill })
    .ResizeObserver = ResizeObserverPolyfill;
}
