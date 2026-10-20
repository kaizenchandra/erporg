import { test, expect } from "@playwright/test";
test("maximum-length calendar presentation stays readable within the mobile dialog", async ({
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
  await section.getByLabel("Calendar code or name").fill("CAL-UI");
  await section
    .getByRole("button", { name: "Search calendars", exact: true })
    .click();
  await section
    .getByRole("button", { name: "Inspect calendar · CAL-UI", exact: true })
    .click();
  await expect(
    page
      .getByRole("dialog")
      .getByRole("heading", { name: "Creation audit", exact: true }),
  ).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  // Presentation-only maximum text fixture; no API or database mutation.
  const sizes = await page.getByRole("dialog").evaluate((dialog) => {
    dialog.querySelector("header h2")!.textContent =
      "Company calendar · " + "W".repeat(30);
    dialog.querySelector("h3")!.textContent = "W".repeat(120);
    const paragraphs = dialog.querySelectorAll(".change-form > p");
    paragraphs[paragraphs.length - 2]!.textContent = "李".repeat(500);
    return { width: dialog.clientWidth, scrollWidth: dialog.scrollWidth };
  });
  expect(sizes.scrollWidth).toBeLessThanOrEqual(sizes.width);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
