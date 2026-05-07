import { describe, expect, it, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';

const subscribe = vi.fn(() => () => {});

vi.mock('@/realtime/websocketManager', () => ({
  websocketManager: { subscribe },
}));

const { useWebSocket } = await import('../useWebSocket');

describe('useWebSocket', () => {
  beforeEach(() => {
    subscribe.mockClear();
  });

  it('exposes a typed subscribe that delegates to the singleton manager', () => {
    const { result } = renderHook(() => useWebSocket());
    const handler = vi.fn();
    const off = result.current.subscribe('query.status_changed', handler);
    expect(subscribe).toHaveBeenCalledWith('query.status_changed', handler);
    expect(typeof off).toBe('function');
  });

  it('returns the unsubscribe function from the manager', () => {
    const teardown = vi.fn();
    subscribe.mockReturnValueOnce(teardown);
    const { result } = renderHook(() => useWebSocket());
    const off = result.current.subscribe('query.executed', () => {});
    off();
    expect(teardown).toHaveBeenCalled();
  });
});
