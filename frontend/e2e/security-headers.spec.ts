import { test, expect } from "@playwright/test";

test.describe("AAL production security headers", () => {
  test("public response has hardened browser policies", async ({ request }) => {
    const response = await request.get("/");
    expect(response.ok()).toBeTruthy();

    const headers = response.headers();
    expect(headers["x-content-type-options"]).toBe("nosniff");
    expect(headers["x-frame-options"]).toBe("DENY");
    expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");

    const csp = headers["content-security-policy"] || "";
    expect(csp).toContain("script-src 'self'");
    expect(csp).toContain("strict-dynamic");
    expect(csp).not.toContain("unsafe-eval");
    expect(csp).not.toContain("script-src 'self' 'unsafe-inline'");
  });

  test("login route hydrates under the production CSP", async ({ page }) => {
    const violations: string[] = [];
    page.on("console", (message) => {
      if (
        message.type() === "error" &&
        /content security policy|refused to execute|blocked by csp/i.test(
          message.text(),
        )
      ) {
        violations.push(message.text());
      }
    });
    page.on("pageerror", (error) => {
      violations.push(error.message);
    });

    await page.goto("/login", { waitUntil: "domcontentloaded" });
    await expect(page.getByLabel("Operations email")).toBeVisible({
      timeout: 10_000,
    });
    await expect(page.getByLabel("Password")).toBeVisible({ timeout: 10_000 });
    expect(violations).toEqual([]);
  });

  test("legacy customer portal aliases redirect away", async ({ page }) => {
    await page.goto("/customer-portal");
    await expect(page).not.toHaveURL(/customer-portal/);
  });
});
