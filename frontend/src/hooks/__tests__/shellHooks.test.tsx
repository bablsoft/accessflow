import { describe, expect, it, vi } from 'vitest';
import { act, render, renderHook } from '@testing-library/react';
import { useRef } from 'react';
import { useMediaQuery } from '../useMediaQuery';
import { useConnectionStatus } from '../useConnectionStatus';
import { useEnterAnimation } from '@/motion/useEnterAnimation';
import { websocketManager } from '@/realtime/websocketManager';

function stubMatchMedia(initial: boolean) {
  const listeners = new Set<() => void>();
  let matches = initial;
  const original = window.matchMedia;
  window.matchMedia = ((query: string) => ({
    get matches() {
      return matches;
    },
    media: query,
    addEventListener: (_: string, cb: () => void) => listeners.add(cb),
    removeEventListener: (_: string, cb: () => void) => listeners.delete(cb),
  })) as unknown as typeof window.matchMedia;
  return {
    set(next: boolean) {
      matches = next;
      listeners.forEach((cb) => cb());
    },
    listeners,
    restore: () => {
      window.matchMedia = original;
    },
  };
}

describe('useMediaQuery', () => {
  it('follows the media query and unsubscribes on unmount', () => {
    const mq = stubMatchMedia(false);
    try {
      const { result, unmount } = renderHook(() => useMediaQuery('(max-width: 1024px)'));
      expect(result.current).toBe(false);
      act(() => mq.set(true));
      expect(result.current).toBe(true);
      unmount();
      expect(mq.listeners.size).toBe(0);
    } finally {
      mq.restore();
    }
  });
});

describe('useConnectionStatus', () => {
  it('mirrors the websocket manager', () => {
    const manager = websocketManager as unknown as { setStatus: (s: string) => void };
    const { result } = renderHook(() => useConnectionStatus());
    expect(result.current).toBe('idle');
    act(() => manager.setStatus('open'));
    expect(result.current).toBe('open');
    act(() => manager.setStatus('idle'));
  });
});

describe('useEnterAnimation', () => {
  function Host({ k }: { k: string }) {
    const ref = useRef<HTMLDivElement>(null);
    useEnterAnimation(ref, k);
    return <div ref={ref} data-testid="host" />;
  }

  it('animates on each key change and cancels the previous run', () => {
    const cancel = vi.fn();
    const animate = vi.fn(() => ({ cancel }));
    (HTMLElement.prototype as unknown as { animate: unknown }).animate = animate;
    try {
      const { rerender } = render(<Host k="/a" />);
      expect(animate).toHaveBeenCalledTimes(1);
      rerender(<Host k="/b" />);
      expect(cancel).toHaveBeenCalledTimes(1);
      expect(animate).toHaveBeenCalledTimes(2);
    } finally {
      delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
    }
  });

  it('does nothing under reduced motion or without Web Animations', () => {
    render(<Host k="/a" />);
    const animate = vi.fn(() => ({ cancel: vi.fn() }));
    (HTMLElement.prototype as unknown as { animate: unknown }).animate = animate;
    const mq = stubMatchMedia(true);
    try {
      render(<Host k="/a" />);
      expect(animate).not.toHaveBeenCalled();
    } finally {
      mq.restore();
      delete (HTMLElement.prototype as unknown as { animate?: unknown }).animate;
    }
  });
});
