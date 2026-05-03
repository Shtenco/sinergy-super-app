// ═══════════════════════════════════════════════════════════════════════════
//  SINERGY NODE — Service Worker (Offline-first PWA)
// ═══════════════════════════════════════════════════════════════════════════

const CACHE_NAME = 'sinergy-node-v1';
const STATIC_ASSETS = [
  '/',
  '/index.html',
  '/manifest.json',
];

// ── Install ────────────────────────────────────────────────────────
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(CACHE_NAME).then(cache => cache.addAll(STATIC_ASSETS))
  );
  self.skipWaiting();
});

// ── Activate ───────────────────────────────────────────────────────
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(names =>
      Promise.all(names.filter(n => n !== CACHE_NAME).map(n => caches.delete(n)))
    )
  );
  self.clients.claim();
});

// ── Fetch (offline-first for static, network-first for API) ──────
self.addEventListener('fetch', event => {
  const url = new URL(event.request.url);

  // Network-first для API/WebSocket
  if (url.pathname.startsWith('/api') || url.pathname.startsWith('/ws')) {
    event.respondWith(
      fetch(event.request).catch(() => caches.match(event.request))
    );
    return;
  }

  // Cache-first для статики
  event.respondWith(
    caches.match(event.request).then(cached => {
      if (cached) return cached;
      return fetch(event.request).then(response => {
        if (response.ok) {
          const clone = response.clone();
          caches.open(CACHE_NAME).then(cache => cache.put(event.request, clone));
        }
        return response;
      }).catch(() => caches.match('/index.html'));
    })
  );
});

// ── Mesh message relay через service worker ──────────────────────
self.addEventListener('message', event => {
  if (event.data && event.data.type === 'MESH_BROADCAST') {
    // Relay mesh message to all clients
    self.clients.matchAll().then(clients => {
      clients.forEach(client => {
        if (client.id !== event.source.id) {
          client.postMessage(event.data);
        }
      });
    });
  }
});
