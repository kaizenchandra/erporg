import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
test("final mobile department filters and resolved parent remain readable for employee", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("sophia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const directory = page.getByRole("region", {
    name: "Department directory",
    exact: true,
  });
  await directory
    .getByLabel("Search departments", { exact: true })
    .fill("BA-01");
  await expect(directory).toContainText(
    "Parent: ZZZ BA directory parent (BA-00)",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await directory.evaluate((element) => {
    element.scrollIntoView({ block: "start" });
    window.scrollBy(0, -85);
  });
  await expect(
    directory.getByRole("heading", { name: "Browse departments", exact: true }),
  ).toBeVisible();
  await directory.getByLabel("Search departments", { exact: true }).focus();
  await page.keyboard.press("Tab");
  await expect(
    directory.getByRole("combobox", { name: "Department timing", exact: true }),
  ).toBeFocused();
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
    path: "docs/evidence/department-directory-controls-mobile.png",
  });
  await directory.locator("article").evaluate((element) => {
    element.scrollIntoView({ block: "start" });
    window.scrollBy(0, -85);
  });
  await page.screenshot({
    path: "docs/evidence/department-directory-parent-mobile.png",
  });
});
