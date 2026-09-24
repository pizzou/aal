import { test, expect } from "@playwright/test";

test.describe("AAL production authentication contract", () => {
  test("public login and protected workspace contract", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByText(/Welcome back|Verify your sign-in/i)).toBeVisible();
    await page.goto("/aal-control-tower");
    await expect(page).toHaveURL(/\/login/);
  });
});
