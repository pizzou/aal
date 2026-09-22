const CACHE = "aal-shell-v1";
const SHELL = ["/", "/login", "/mobile-ops", "/branding/aal-logo.jpg"];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (url.pathname.startsWith("/api/")) return;

  // Never cache authenticated application routes. Offline support is limited to the
  // public shell and explicit static assets so one user's dashboard cannot become
  // another user's offline cache after logout/login on a shared device.
  const cacheable =
    url.pathname === "/" ||
    url.pathname === "/login" ||
    url.pathname === "/mobile-ops" ||
    url.pathname.startsWith("/_next/static/") ||
    url.pathname === "/branding/aal-logo.jpg";

  if (!cacheable) {
    event.respondWith(fetch(request));
    return;
  }

  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy)).catch(() => {});
        }
        return response;
      })
      .catch(() => caches.match(request).then((cached) => cached || caches.match("/"))),
  );
});
