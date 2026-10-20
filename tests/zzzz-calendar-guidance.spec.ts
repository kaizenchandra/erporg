import { test, expect } from "@playwright/test";
test("calendar guidance distinguishes original creation from working dated amendments", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  const section = page.getByRole("region", {
    name: "Company calendars",
    exact: true,
  });
  await expect(
    section.getByText("show original creation policy", { exact: false }),
  ).toBeVisible();
  await section
    .getByRole("button", { name: "Create company calendar", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "Changes use dated amendments",
  );
  await expect(page.getByRole("dialog")).not.toContainText(
    "amendments require a later",
  );
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await section.getByLabel("Calendar code or name").fill("AMEND-UI");
  await section
    .getByRole("button", { name: "Search calendars", exact: true })
    .click();
  await section
    .getByRole("button", { name: "Inspect calendar · AMEND-UI", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Amend working policy", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Amendment history", exact: true }),
  ).toBeVisible();
});
