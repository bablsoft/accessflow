import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';

const { getVapidPublicKey, subscribePush, unsubscribePush } = vi.hoisted(() => ({
  getVapidPublicKey: vi.fn(),
  subscribePush: vi.fn(),
  unsubscribePush: vi.fn(),
}));
vi.mock('@/api/push', () => ({ getVapidPublicKey, subscribePush, unsubscribePush }));

const { isPushSupported, serializePushSubscription, urlBase64ToUint8Array } = vi.hoisted(() => ({
  isPushSupported: vi.fn(),
  serializePushSubscription: vi.fn(),
  urlBase64ToUint8Array: vi.fn(),
}));
vi.mock('@/utils/push', () => ({ isPushSupported, serializePushSubscription, urlBase64ToUint8Array }));

import { usePushSubscription } from '../usePushSubscription';

function stubServiceWorker(registration: unknown) {
  Object.defineProperty(navigator, 'serviceWorker', {
    value: { ready: Promise.resolve(registration) },
    configurable: true,
  });
}

describe('usePushSubscription', () => {
  beforeEach(() => {
    getVapidPublicKey.mockReset().mockResolvedValue('PUB');
    subscribePush.mockReset().mockResolvedValue(undefined);
    unsubscribePush.mockReset().mockResolvedValue(undefined);
    isPushSupported.mockReturnValue(true);
    serializePushSubscription.mockReturnValue({
      endpoint: 'https://push/abc',
      keys: { p256dh: 'P', auth: 'A' },
    });
    urlBase64ToUint8Array.mockReturnValue(new Uint8Array([1]));
    vi.stubGlobal('Notification', {
      permission: 'default' as NotificationPermission,
      requestPermission: vi.fn().mockResolvedValue('granted'),
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    Object.defineProperty(navigator, 'serviceWorker', { value: undefined, configurable: true });
  });

  it('reports no existing subscription on mount', async () => {
    stubServiceWorker({ pushManager: { getSubscription: vi.fn().mockResolvedValue(null) } });
    const { result } = renderHook(() => usePushSubscription());
    await waitFor(() => expect(result.current.enabled).toBe(false));
    expect(result.current.supported).toBe(true);
  });

  it('enable() subscribes via the SW and registers with the backend', async () => {
    const subscribe = vi.fn().mockResolvedValue({ endpoint: 'https://push/abc' });
    stubServiceWorker({
      pushManager: { getSubscription: vi.fn().mockResolvedValue(null), subscribe },
    });
    const { result } = renderHook(() => usePushSubscription());

    await act(async () => {
      await result.current.enable();
    });

    expect(subscribe).toHaveBeenCalledWith({
      userVisibleOnly: true,
      applicationServerKey: expect.any(Uint8Array),
    });
    expect(subscribePush).toHaveBeenCalledTimes(1);
    expect(result.current.enabled).toBe(true);
  });

  it('enable() returns false and skips the backend when permission is denied', async () => {
    vi.stubGlobal('Notification', {
      permission: 'default' as NotificationPermission,
      requestPermission: vi.fn().mockResolvedValue('denied'),
    });
    const subscribe = vi.fn();
    stubServiceWorker({
      pushManager: { getSubscription: vi.fn().mockResolvedValue(null), subscribe },
    });
    const { result } = renderHook(() => usePushSubscription());

    let ok = true;
    await act(async () => {
      ok = await result.current.enable();
    });

    expect(ok).toBe(false);
    expect(subscribe).not.toHaveBeenCalled();
    expect(subscribePush).not.toHaveBeenCalled();
  });

  it('disable() unsubscribes locally and removes the backend subscription', async () => {
    const unsubscribe = vi.fn().mockResolvedValue(true);
    const subscription = { endpoint: 'https://push/abc', unsubscribe };
    stubServiceWorker({
      pushManager: { getSubscription: vi.fn().mockResolvedValue(subscription) },
    });
    const { result } = renderHook(() => usePushSubscription());

    await act(async () => {
      await result.current.disable();
    });

    expect(unsubscribe).toHaveBeenCalled();
    expect(unsubscribePush).toHaveBeenCalledWith('https://push/abc');
    expect(result.current.enabled).toBe(false);
  });

  it('is inert when push is unsupported', async () => {
    isPushSupported.mockReturnValue(false);
    const { result } = renderHook(() => usePushSubscription());

    expect(result.current.supported).toBe(false);
    let ok = true;
    await act(async () => {
      ok = await result.current.enable();
    });
    expect(ok).toBe(false);
    expect(subscribePush).not.toHaveBeenCalled();
  });
});
