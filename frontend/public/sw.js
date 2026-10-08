const CACHE = "aal-static-v4";
const STATIC_ASSETS = ["/branding/aal-logo.jpg"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(STATIC_ASSETS))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) =>
        Promise.all(
          keys
            .filter((key) => key !== CACHE)
            .map((key) => caches.delete(key)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Next.js HTML, RSC payloads and authenticated APIs must never be served
  // from this service worker. Vercel/Next handles the correct cache policy.
  if (
    url.pathname.startsWith("/api/") ||
    url.pathname.startsWith("/_next/") ||
    url.pathname === "/" ||
    url.pathname.startsWith("/login") ||
    url.pathname.startsWith("/aal-control-tower") ||
    url.pathname.startsWith("/billing") ||
    url.pathname.startsWith("/finance/") ||
    url.pathname.startsWith("/reports")
  ) {
    return;
  }

  if (url.pathname === "/branding/aal-logo.jpg") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok) {
            void caches
              .open(CACHE)
              .then((cache) => cache.put(request, response.clone()))
              .catch(() => undefined);
          }
          return response;
        })
        .catch(() =>
          caches
            .match(request)
            .then((cached) => cached || Response.error()),
        ),
    );
  }
});
