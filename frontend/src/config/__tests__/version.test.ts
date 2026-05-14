import { describe, expect, it } from 'vitest';

import { APP_VERSION } from '@/config/version';

describe('APP_VERSION', () => {
  it('is a non-empty semver-ish string injected at build time', () => {
    expect(typeof APP_VERSION).toBe('string');
    expect(APP_VERSION).not.toBe('');
    expect(APP_VERSION).toMatch(/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/);
  });
});
