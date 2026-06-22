import { useCallback, useEffect, useState } from 'react';
import { getVapidPublicKey, subscribePush, unsubscribePush } from '@/api/push';
import { isPushSupported, serializePushSubscription, urlBase64ToUint8Array } from '@/utils/push';

export type PushPermission = NotificationPermission | 'unsupported';

export interface UsePushSubscription {
  supported: boolean;
  permission: PushPermission;
  enabled: boolean;
  busy: boolean;
  /** Resolves true when a subscription was created, false when permission was not granted. */
  enable: () => Promise<boolean>;
  disable: () => Promise<void>;
}

/**
 * Owns the browser side of the Web Push opt-in (AF-444): requesting notification permission,
 * subscribing via the registered service worker with the deployment VAPID key, and registering /
 * removing the subscription on the backend. Plain imperative state — a device capability toggle,
 * not cached server data.
 */
export function usePushSubscription(): UsePushSubscription {
  const supported = isPushSupported();
  const [permission, setPermission] = useState<PushPermission>(
    supported ? Notification.permission : 'unsupported',
  );
  const [enabled, setEnabled] = useState(false);
  const [busy, setBusy] = useState(false);

  const refresh = useCallback(async () => {
    if (!supported) {
      return;
    }
    const registration = await navigator.serviceWorker.ready;
    const subscription = await registration.pushManager.getSubscription();
    setEnabled(Boolean(subscription));
  }, [supported]);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const enable = useCallback(async (): Promise<boolean> => {
    if (!supported) {
      return false;
    }
    setBusy(true);
    try {
      const granted = await Notification.requestPermission();
      setPermission(granted);
      if (granted !== 'granted') {
        return false;
      }
      const registration = await navigator.serviceWorker.ready;
      const publicKey = await getVapidPublicKey();
      const subscription = await registration.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(publicKey),
      });
      await subscribePush(serializePushSubscription(subscription), navigator.userAgent);
      setEnabled(true);
      return true;
    } finally {
      setBusy(false);
    }
  }, [supported]);

  const disable = useCallback(async () => {
    if (!supported) {
      return;
    }
    setBusy(true);
    try {
      const registration = await navigator.serviceWorker.ready;
      const subscription = await registration.pushManager.getSubscription();
      if (subscription) {
        const { endpoint } = subscription;
        await subscription.unsubscribe();
        await unsubscribePush(endpoint);
      }
      setEnabled(false);
    } finally {
      setBusy(false);
    }
  }, [supported]);

  return { supported, permission, enabled, busy, enable, disable };
}
