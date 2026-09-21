import { test, expect } from "@playwright/test";

test.describe("AAL public flows", () => {
  test("home renders", async ({ page }) => {
    await page.goto("/");
    await expect(page).toHaveTitle(/AAL|Logistics|Aviation Africa/i);
  });

  test("quote flow is reachable", async ({ page }) => {
    await page.goto("/quote");
    await expect(page).toHaveURL(/\/quote/);
    await expect(page.getByRole("main")).toBeVisible();
  });

  test("booking flow is reachable", async ({ page }) => {
    await page.goto("/book");
    await expect(page).toHaveURL(/\/book/);
  });

  test("tracking flow is reachable", async ({ page }) => {
    await page.goto("/track");
    await expect(page).toHaveURL(/\/track/);
    await expect(page.getByRole("textbox")).toBeVisible();
  });

  test("login route renders", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("main")).toBeVisible();
  });
});
