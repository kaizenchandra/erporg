import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
// Read-only presentation fixture; real persistence/privacy uses personal-email.spec.ts.
test("maximum supported personal email remains inside the mobile private profile", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("sophia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  const employee = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  await page
    .getByRole("button", { name: `View ${employee.name}`, exact: true })
    .click();
  const profile = page.getByRole("dialog", {
    name: "Employee profile",
    exact: true,
  });
  const email = profile
    .locator(".detail-grid > div")
    .filter({ has: page.locator("dt", { hasText: /^Personal email$/ }) })
    .locator("dd");
  const maximum = "L".repeat(64) + "@" + "d".repeat(50) + ".test";
  expect(maximum.length).toBe(120);
  await email.evaluate((element, text) => {
    element.textContent = text;
  }, maximum);
  await page.setViewportSize({ width: 390, height: 844 });
  await email.scrollIntoViewIfNeeded();
  expect(
    await profile.evaluate(
      (element) => element.scrollWidth <= element.clientWidth + 1,
    ),
  ).toBe(true);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.screenshot({
    path: "docs/evidence/personal-email-long-mobile.png",
  });
});
