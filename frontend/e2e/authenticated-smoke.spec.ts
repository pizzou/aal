import { test, expect } from "@playwright/test";

type PlaywrightRuntimeGlobal = typeof globalThis & {
  process?: {
    env?: Record<string, string | undefined>;
  };
};

const env = (globalThis as PlaywrightRuntimeGlobal).process?.env ?? {};
const email = env.E2E_USER_EMAIL;
const password = env.E2E_USER_PASSWORD;

test.describe("AAL authenticated smoke", () => {
  test.skip(
    !email || !password,
    "Set E2E_USER_EMAIL and E2E_USER_PASSWORD for authenticated acceptance tests",
  );

  test("login, dashboard and core modules", async ({ page }) => {
    await page.goto("/login");
    await page.getByLabel(/email/i).fill(email!);
    await page.getByLabel(/password/i).fill(password!);
    await page.getByRole("button", { name: /sign in|login/i }).click();

    // If OTP is enabled, the application must present the OTP challenge rather than silently bypassing it.
    if (await page.getByLabel(/otp|verification code/i).count()) {
      const otp = env.E2E_OTP;
      test.skip(
        !otp,
        "OTP is enabled; set E2E_OTP to run the full authenticated smoke",
      );
      await page.getByLabel(/otp|verification code/i).fill(otp!);
      await page
        .getByRole("button", { name: /verify|continue|sign in/i })
        .click();
    }

    await page.waitForURL(/\/aal-control-tower/);
    await expect(page.locator("body")).not.toContainText(
      "Request failed with status 403",
    );
    await expect(page.locator("body")).not.toContainText(
      "Request failed with status 401",
    );

    for (const route of [
      "/aal-control-tower",
      "/shipments",
      "/mobile-ops",
      "/documents",
      "/customers",
      "/commercial",
      "/billing",
    ]) {
      await page.goto(route);
      await expect(page.locator("body")).not.toContainText(
        "Request failed with status 403",
      );
      await expect(page.locator("body")).not.toContainText(
        "Request failed with status 401",
      );
    }
  });
});
