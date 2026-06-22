import { describe, expect, it } from 'vitest';
import {
  arrayBufferToBase64Url,
  isPushSupported,
  serializePushSubscription,
  urlBase64ToUint8Array,
} from './push';

describe('utils/push', () => {
  it('urlBase64ToUint8Array decodes base64url to bytes', () => {
    // "hello" -> base64url "aGVsbG8"
    const bytes = urlBase64ToUint8Array('aGVsbG8');
    expect(Array.from(bytes)).toEqual([104, 101, 108, 108, 111]);
  });

  it('urlBase64ToUint8Array handles url-safe chars and missing padding', () => {
    const bytes = urlBase64ToUint8Array('-_8');
    expect(Array.from(bytes)).toEqual([251, 255]);
  });

  it('arrayBufferToBase64Url round-trips with the decoder', () => {
    const source = new Uint8Array([251, 255, 0, 17]);
    const encoded = arrayBufferToBase64Url(source.buffer);
    expect(encoded).not.toContain('+');
    expect(encoded).not.toContain('/');
    expect(encoded).not.toContain('=');
    expect(Array.from(urlBase64ToUint8Array(encoded))).toEqual([251, 255, 0, 17]);
  });

  it('arrayBufferToBase64Url returns empty for null', () => {
    expect(arrayBufferToBase64Url(null)).toBe('');
  });

  it('serializePushSubscription prefers the toJSON keys', () => {
    const subscription = {
      endpoint: 'https://push.example/abc',
      toJSON: () => ({ endpoint: 'https://push.example/abc', keys: { p256dh: 'P', auth: 'A' } }),
      getKey: () => null,
    } as unknown as PushSubscription;

    expect(serializePushSubscription(subscription)).toEqual({
      endpoint: 'https://push.example/abc',
      keys: { p256dh: 'P', auth: 'A' },
    });
  });

  it('serializePushSubscription falls back to getKey when toJSON omits keys', () => {
    const subscription = {
      endpoint: 'https://push.example/xyz',
      toJSON: () => ({ endpoint: 'https://push.example/xyz', keys: {} }),
      getKey: () => new Uint8Array([1, 2, 3]).buffer,
    } as unknown as PushSubscription;

    const result = serializePushSubscription(subscription);
    expect(result.keys.p256dh).toBe(arrayBufferToBase64Url(new Uint8Array([1, 2, 3]).buffer));
  });

  it('isPushSupported is false without PushManager', () => {
    expect(isPushSupported()).toBe(false);
  });

  it('isPushSupported is true when SW, PushManager and Notification exist', () => {
    const original = {
      sw: (navigator as { serviceWorker?: unknown }).serviceWorker,
      pm: (window as { PushManager?: unknown }).PushManager,
      n: (window as { Notification?: unknown }).Notification,
    };
    Object.defineProperty(navigator, 'serviceWorker', { value: {}, configurable: true });
    (window as { PushManager?: unknown }).PushManager = function () {};
    (window as { Notification?: unknown }).Notification = function () {};
    try {
      expect(isPushSupported()).toBe(true);
    } finally {
      Object.defineProperty(navigator, 'serviceWorker', {
        value: original.sw,
        configurable: true,
      });
      (window as { PushManager?: unknown }).PushManager = original.pm;
      (window as { Notification?: unknown }).Notification = original.n;
    }
  });
});
