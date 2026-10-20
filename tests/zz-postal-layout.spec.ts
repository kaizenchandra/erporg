import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
// Read-only UI stress fixture: no request/response mocks and no business-data writes.
// Real submission/approval/persistence are covered by private-postal-address.spec.ts.
test("maximum-length postal presentation fixture stays inside the mobile profile", async ({
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
  const postal = profile.getByRole("region", {
    name: "Structured postal address",
  });
  await expect(postal.locator("address")).toBeVisible();
  const long = [
    "L".repeat(120),
    "U".repeat(120),
    "C".repeat(80),
    "R".repeat(80),
    "P".repeat(30),
    "US",
  ].join(", ");
  await postal.locator("address").evaluate((element, text) => {
    element.textContent = text;
  }, long);
  await profile
    .locator(".history-event p")
    .first()
    .evaluate((element, text) => {
      element.textContent = text;
    }, long);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await profile.evaluate(
      (element) => element.scrollWidth <= element.clientWidth + 1,
    ),
  ).toBe(true);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await postal.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/postal-address-long-mobile.png",
  });
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await profile
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("address");
  const editor = page.getByRole("dialog", {
    name: "Request an employee change",
    exact: true,
  });
  await editor.locator(".current-value strong").evaluate((element, text) => {
    element.textContent = text;
  }, long);
  expect(
    await editor.evaluate(
      (element) => element.scrollWidth <= element.clientWidth + 1,
    ),
  ).toBe(true);
});

test("maximum-length comparison fixture stays inside mobile approval cards", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page.getByRole("button", { name: "All requests", exact: true }).click();
  const card = page.locator(".request-card").first();
  await expect(card).toBeVisible();
  const long = [
    "L".repeat(120),
    "U".repeat(120),
    "C".repeat(80),
    "R".repeat(80),
    "P".repeat(30),
    "US",
  ].join(", ");
  await card
    .locator(".request-content p")
    .first()
    .evaluate((element, text) => {
      for (const node of element.childNodes)
        if (node.nodeType === Node.TEXT_NODE) {
          node.textContent = text;
          break;
        }
      const next = element.querySelector("b");
      if (next) next.textContent = text;
    }, long);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    await card.evaluate(
      (element) => element.scrollWidth <= element.clientWidth + 1,
    ),
  ).toBe(true);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await card.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/postal-address-long-approval-mobile.png",
  });
});
