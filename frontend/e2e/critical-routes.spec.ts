import { test, expect } from "@playwright/test";

/**
 * Critical internal routes must exist as real Next.js routes. Authentication
 * is intentionally not required here: a protected route may redirect to
 * /login, but it must never return a framework-level 404.
 */
test.describe("AAL critical internal routes", () => {
  for (const route of [
    "/air-cargo",
    "/air-cargo/bookings",
    "/air-cargo/integrations",
  ]) {
    test(`${route} is deployed`, async ({ request }) => {
      const response = await request.get(route, {
        maxRedirects: 5,
      });
      expect(response.status()).not.toBe(404);
      expect(response.status()).toBeLessThan(500);
    });
  }
});
