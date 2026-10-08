"use client";

import { useEffect } from "react";

const SERVICE_WORKER_URL = "/sw.js?v=4";

export default function ServiceWorkerRegistration() {
  useEffect(() => {
    if (!("serviceWorker" in navigator) || !window.isSecureContext) {
      return;
    }

    let cancelled = false;

    void navigator.serviceWorker
      .register(SERVICE_WORKER_URL, { updateViaCache: "none" })
      .then((registration) => {
        if (cancelled) return;

        // Make the latest worker eligible immediately after a deployment.
        void registration.update().catch(() => undefined);
      })
      .catch(() => undefined);

    return () => {
      cancelled = true;
    };
  }, []);

  return null;
}
