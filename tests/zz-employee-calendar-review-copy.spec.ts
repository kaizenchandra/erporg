import { test, expect } from "@playwright/test";
// Read-only check of retained approval history; never submits or edits business data.
test("retained calendar unassignment uses calendar-specific scope in list and review", async ({
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
  const list = page.locator(".inbox-list");
  const cards = list
    .locator(".request-card")
    .filter({ hasText: "Company calendar assignment" })
    .filter({ hasText: "Unassign calendar" });
  let found = false;
  for (let n = 0; n < 20; n++) {
    await expect(list).toHaveAttribute("aria-busy", "false");
    if (await cards.count()) {
      found = true;
      break;
    }
    const next = page.getByRole("button", {
      name: "Next requests",
      exact: true,
    });
    if (await next.isDisabled()) break;
    await next.click();
  }
  expect(found).toBe(true);
  await cards
    .first()
    .getByRole("button", { name: "View request", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("Unassign calendar", { exact: true }),
  ).toBeVisible();
  await expect(dialog).not.toContainText("Unassign position");
  await expect(
    dialog.getByRole("region", { name: "Calendar assignment impact" }),
  ).toContainText("Unassignment");
  await expect(
    dialog.getByRole("button", { name: "Confirm approval", exact: true }),
  ).toHaveCount(0);
});
