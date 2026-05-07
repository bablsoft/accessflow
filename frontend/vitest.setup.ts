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
