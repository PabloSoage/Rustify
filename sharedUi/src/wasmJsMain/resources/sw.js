// The web interface installed as an app (a PWA): this keeps the page's own files — the HTML, the
// script, the wasm, the strings and images — so it opens at once and with the server slow. Never the
// API: a song, a call to the core, the login, the files are always asked of the server, and a
// redirect (to /login, with no session) is not kept either, or the app would open on the login page.
const CACHE = 'rustify-ui-v1';

self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

function isInterface(url) {
  if (url.origin !== self.location.origin) return false;
  const path = url.pathname;
  return !(path.includes('/api/') || path.endsWith('/login') || path.endsWith('/logout') || path.endsWith('/healthz'));
}

self.addEventListener('fetch', (event) => {
  const request = event.request;
  if (request.method !== 'GET' || !isInterface(new URL(request.url))) return;
  // The network first, so a new build is seen at once; what was kept answers when it cannot.
  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok && !response.redirected && response.type === 'basic') {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy));
        }
        return response;
      })
      .catch(() => caches.match(request).then((kept) => kept || Response.error()))
  );
});
