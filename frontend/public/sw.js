const CACHE = "aal-static-v3";

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.add("/branding/aal-logo.jpg"))
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
            .filter(
              (key) =>
                key.startsWith("aal-shell-") ||
                key.startsWith("aal-static-") ||
                key !== CACHE && key.includes("aal-"),
            )
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
  if (request.mode === "navigate") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  // Never intercept API, RSC/Flight, or application HTML requests. Those must
  // always be served by Next.js so authentication and route rendering cannot be
  // trapped behind a stale service-worker response.
  if (
    url.pathname.startsWith("/api/") ||
    url.pathname.startsWith("/_next/") && !url.pathname.startsWith("/_next/static/") ||
    request.headers.has("RSC") ||
    request.headers.has("Next-Router-State-Tree") ||
    request.headers.has("Next-Url")
  ) {
    return;
  }

  const cacheable =
    url.pathname.startsWith("/_next/static/") ||
    url.pathname === "/branding/aal-logo.jpg" ||
    url.pathname === "/manifest.webmanifest";

  if (!cacheable) return;

  event.respondWith(
    caches.match(request).then((cached) => {
      const network = fetch(request)
        .then((response) => {
          if (response.ok) {
            const copy = response.clone();
            void caches
              .open(CACHE)
              .then((cache) => cache.put(request, copy))
              .catch(() => undefined);
          }
          return response;
        })
        .catch(() => cached);

      return cached || network;
    }),
  );
});
