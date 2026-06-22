/**
 * Pure helpers for the Web Push subscription flow (AF-444). Kept framework-free so they are
 * unit-testable in jsdom and reusable by both the hook and the service-worker bridge.
 */

export interface SerializedSubscription {
  endpoint: string;
  keys: { p256dh: string; auth: string };
}

/** Decodes a base64url VAPID public key into the `Uint8Array` `pushManager.subscribe` expects. */
export function urlBase64ToUint8Array(base64String: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64String.length % 4)) % 4);
  const base64 = (base64String + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(base64);
  const output = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i += 1) {
    output[i] = raw.charCodeAt(i);
  }
  return output;
}

/** Encodes a raw subscription key buffer (p256dh / auth) as base64url. */
export function arrayBufferToBase64Url(buffer: ArrayBuffer | null): string {
  if (!buffer) {
    return '';
  }
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) {
    binary += String.fromCharCode(bytes[i] as number);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Normalises a browser `PushSubscription` into the snake_case-friendly wire shape. */
export function serializePushSubscription(subscription: PushSubscription): SerializedSubscription {
  const json = subscription.toJSON();
  return {
    endpoint: subscription.endpoint,
    keys: {
      p256dh: json.keys?.p256dh ?? arrayBufferToBase64Url(subscription.getKey('p256dh')),
      auth: json.keys?.auth ?? arrayBufferToBase64Url(subscription.getKey('auth')),
    },
  };
}

/** Whether this browser can run the PWA push flow at all. */
export function isPushSupported(): boolean {
  return (
    typeof navigator !== 'undefined' &&
    'serviceWorker' in navigator &&
    typeof window !== 'undefined' &&
    'PushManager' in window &&
    'Notification' in window
  );
}
