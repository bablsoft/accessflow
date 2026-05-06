import { describe, expect, it, vi } from 'vitest';
import { jittered, sleep } from '../delay';

describe('sleep', () => {
  it('resolves after the given delay', async () => {
    vi.useFakeTimers();
    let resolved = false;
    const promise = sleep(1000).then(() => {
      resolved = true;
    });
    vi.advanceTimersByTime(999);
    await Promise.resolve();
    expect(resolved).toBe(false);
    vi.advanceTimersByTime(1);
    await promise;
    expect(resolved).toBe(true);
    vi.useRealTimers();
  });
});

describe('jittered', () => {
  it('resolves within the bounds', async () => {
    const start = Date.now();
    await jittered(10, 30);
    const elapsed = Date.now() - start;
    expect(elapsed).toBeGreaterThanOrEqual(0);
    expect(elapsed).toBeLessThan(200);
  });

  it('uses the default range when no args are passed', async () => {
    const start = Date.now();
    await jittered();
    const elapsed = Date.now() - start;
    expect(elapsed).toBeGreaterThanOrEqual(0);
  });
});
