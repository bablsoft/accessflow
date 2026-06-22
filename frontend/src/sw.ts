/// <reference lib="webworker" />
/*
 * AccessFlow service worker (AF-444). Built by vite-plugin-pwa (injectManifest), so it owns:
 *   - an offline-capable app shell: precache the Workbox manifest + index.html, and serve
 *     cached index.html for navigations when the network is unavailable (the review queue shell);
 *   - Web Push: render the one-tap review notification with approve/reject actions;
 *   - notificationclick: deep-link into /reviews/{id}/decide (carrying the chosen action) and
 *     focus an existing tab if one is open.
 *
 * Self-contained (no workbox-runtime imports) to keep the bundle tiny and the dependency surface
 * flat; `self.__WB_MANIFEST` is replaced at build time with the precache file list.
 */
declare const self: ServiceWorkerGlobalScope & {
  __WB_MANIFEST: { url: string; revision: string | null }[];
};

interface PushPayload {
  title?: string;
  body?: string;
  data?: { url?: string; queryId?: string };
  actions?: { action: string; title: string }[];
}

const SHELL_CACHE = 'accessflow-shell-v1';
const SHELL_URLS = ['/', '/index.html', ...self.__WB_MANIFEST.map((entry) => entry.url)];

self.addEventListener('install', (event) => {
  // Best-effort precache: cache entries individually so one transiently-failing asset doesn't
  // abort the whole install (cache.addAll is atomic). Keeps the SW installable under load.
  event.waitUntil(
    caches
      .open(SHELL_CACHE)
      .then((cache) => Promise.allSettled(SHELL_URLS.map((url) => cache.add(url))))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(keys.filter((key) => key !== SHELL_CACHE).map((key) => caches.delete(key))),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || request.mode !== 'navigate') {
    return;
  }
  // App shell: try the network first, fall back to the cached index.html when offline.
  event.respondWith(
    fetch(request).catch(async () => {
      const cached = await caches.match('/index.html');
      return cached ?? Response.error();
    }),
  );
});

self.addEventListener('push', (event) => {
  let payload: PushPayload = {};
  try {
    payload = (event.data?.json() as PushPayload | undefined) ?? {};
  } catch {
    payload = { body: event.data?.text() };
  }
  const data = payload.data ?? {};
  event.waitUntil(
    self.registration.showNotification(payload.title ?? 'AccessFlow', {
      body: payload.body ?? '',
      icon: '/pwa-icon.svg',
      badge: '/pwa-icon.svg',
      tag: data.queryId,
      data,
      actions: (payload.actions ?? []).map((a) => ({ action: a.action, title: a.title })),
    }),
  );
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const data = (event.notification.data ?? {}) as { url?: string };
  const base = data.url ?? '/reviews';
  const target = event.action ? `${base}?action=${event.action}` : base;
  event.waitUntil(
    self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((clients) => {
      for (const client of clients) {
        if ('focus' in client) {
          void (client as WindowClient).navigate(target);
          return (client as WindowClient).focus();
        }
      }
      return self.clients.openWindow(target);
    }),
  );
});
