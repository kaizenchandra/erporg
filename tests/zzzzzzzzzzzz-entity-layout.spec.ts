import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
}
test("final mobile entity field is visible to self and restricted in the manager profile", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "sophia");
  await page
    .getByRole("button", { name: "Open your profile", exact: true })
    .click();
  let dialog = page.getByRole("dialog");
  let row = dialog
    .locator("dt")
    .filter({ hasText: /^Declared employing entity$/ })
    .locator("..");
  await expect(row.locator("dd")).toHaveText("AY-API");
  await row.scrollIntoViewIfNeeded();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "docs/evidence/entity-assignment-profile-mobile.png",
  });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
  await login(page, "marcus");
  const e = await (await page.request.get("/api/v1/employees/E003")).json();
  await page.getByLabel("Search employees").fill("E003");
  await page
    .getByRole("button", {
      name: `${e.name} ${e.title} ${e.status}`,
      exact: true,
    })
    .click();
  dialog = page.getByRole("dialog");
  row = dialog
    .locator("dt")
    .filter({ hasText: /^Declared employing entity$/ })
    .locator("..");
  await expect(row.locator("dd")).toHaveText("Restricted");
  await expect(dialog).not.toContainText("AY-API");
  await row.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/entity-assignment-manager-mobile.png",
  });
});
