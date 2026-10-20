import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function login(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
}
test("position department selection pages, retains values across errors and stale search, then persists resolved name", async ({
  page,
}) => {
  await login(page);
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  await page.route(
    "**/api/v1/organisation/department-directory?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          status: 503,
          title: "Choices temporarily unavailable",
        }),
      }),
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Create position", exact: true })
    .click();
  const dialog = page.getByRole("dialog"),
    picker = dialog.getByRole("group", {
      name: "Department selection",
      exact: true,
    });
  await expect(picker.getByRole("alert")).toBeFocused();
  await picker
    .getByRole("button", { name: "Retry department choices", exact: true })
    .click();
  await expect(
    picker.getByLabel("Find a department", { exact: true }),
  ).toBeFocused();
  await picker
    .getByRole("button", { name: "Next department choices", exact: true })
    .click();
  await expect(picker.getByRole("status")).toContainText("Page 2");
  await picker
    .getByRole("button", { name: "Previous department choices", exact: true })
    .click();
  await expect(picker.getByRole("status")).toContainText("Page 1");
  await picker.getByLabel("Find a department", { exact: true }).fill("BA-00");
  await picker
    .getByRole("combobox", { name: "Department for position", exact: false })
    .selectOption("BA-00");
  await picker
    .getByLabel("Find a department", { exact: true })
    .fill("no matching department");
  await expect(picker.getByRole("status")).toContainText(
    "No matching departments",
  );
  await expect(picker.getByRole("combobox")).toHaveValue("BA-00");
  let release = () => {};
  const held = new Promise<void>((r) => {
    release = r;
  });
  let ready = () => {};
  const received = new Promise<void>((r) => {
    ready = r;
  });
  let finish = () => {};
  const done = new Promise<void>((r) => {
    finish = r;
  });
  await page.route(
    "**/api/v1/organisation/department-directory?search=Engineering&*",
    async (route) => {
      const response = await route.fetch();
      ready();
      await held;
      await route.fulfill({ response });
      finish();
    },
    { times: 1 },
  );
  await picker
    .getByLabel("Find a department", { exact: true })
    .fill("Engineering");
  await received;
  await picker.getByLabel("Find a department", { exact: true }).fill("BA-01");
  await expect(
    picker.getByRole("combobox").locator('option[value="BA-01"]'),
  ).toBeAttached();
  release();
  await done;
  await expect(
    picker.getByLabel("Find a department", { exact: true }),
  ).toHaveValue("BA-01");
  await expect(picker.getByRole("combobox")).toHaveValue("BA-00");
  await dialog.getByLabel("Position code").fill("BB-UI-POSITION");
  await dialog
    .getByLabel("Position title", { exact: false })
    .fill("BB selected department role");
  await dialog
    .getByLabel("Position configuration reason")
    .fill("Synthetic selected department acceptance");
  await dialog
    .getByRole("button", { name: "Save position", exact: true })
    .click();
  await expect(dialog).toBeHidden();
  const catalogue = page.getByRole("region", {
    name: "Position catalogue",
    exact: true,
  });
  await catalogue.getByLabel("Search positions").fill("BB-UI-POSITION");
  const filter = catalogue.getByRole("group", {
    name: "Department selection",
    exact: true,
  });
  await filter.getByLabel("Find a department", { exact: true }).fill("BA-00");
  await filter
    .getByRole("combobox", { name: "Position department", exact: true })
    .selectOption("BA-00");
  await expect(catalogue.locator(".position-card")).toContainText(
    "ZZZ BA directory parent (BA-00)",
  );
  await checkResponse(
    await page.request.get(
      "/api/v1/organisation/positions?search=BB-UI-POSITION",
    ),
    "/api/v1/organisation/positions",
  );
});
test("hire department selection preserves other fields, linked errors and long mobile options", async ({
  page,
}) => {
  await login(page);
  const csrf = (await (await page.request.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  const name = "BB long " + "界".repeat(80);
  expect(
    (
      await page.request.post("/api/v1/organisation/departments", {
        headers: { [csrf.headerName]: csrf.token },
        data: {
          code: "BB-LONG",
          name,
          parentCode: null,
          costCentre: "DEMO-BB",
          effectiveFrom: new Date().toISOString().slice(0, 10),
          reason: "Synthetic long option acceptance",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(200);
  await page.getByRole("button", { name: "Hiring", exact: true }).click();
  await page
    .getByRole("button", { name: "Submit a hire", exact: true })
    .click();
  const dialog = page.getByRole("dialog"),
    picker = dialog.getByRole("group", {
      name: "Department selection",
      exact: true,
    });
  await dialog.getByLabel("Employee name").fill("Synthetic preserved draft");
  await picker.getByLabel("Find a department", { exact: true }).fill("BB-LONG");
  await picker
    .getByRole("combobox", { name: "Department", exact: false })
    .selectOption(name);
  await picker.getByLabel("Find a department", { exact: true }).press("Enter");
  await expect(
    dialog.getByRole("alert", { name: "Hire request errors", exact: true }),
  ).toBeHidden();
  await page.route(
    "**/api/v1/organisation/department-directory?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          status: 503,
          title: "Choices temporarily unavailable",
        }),
      }),
    { times: 1 },
  );
  await picker.getByLabel("Find a department", { exact: true }).fill("Design");
  await expect(picker.getByRole("alert")).toBeFocused();
  await expect(picker.getByRole("combobox")).toHaveValue(name);
  await expect(dialog.getByLabel("Employee name")).toHaveValue(
    "Synthetic preserved draft",
  );
  await picker
    .getByRole("button", { name: "Retry department choices", exact: true })
    .click();
  await expect(
    picker.getByLabel("Find a department", { exact: true }),
  ).toBeFocused();
  await expect(picker.getByRole("combobox")).toHaveValue(name);
  await page.setViewportSize({ width: 390, height: 844 });
  await picker
    .getByLabel("Find a department", { exact: true })
    .scrollIntoViewIfNeeded();
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
  await picker.getByRole("combobox").evaluate((element) => {
    element.scrollIntoView({ block: "start" });
    const d = element.closest("dialog");
    if (d) d.scrollTop -= 85;
  });
  await page.screenshot({
    path: "docs/evidence/department-selection-mobile.png",
  });
  await dialog
    .getByRole("button", { name: "Submit for HR review", exact: true })
    .click();
  await expect(
    dialog.getByRole("alert", { name: "Hire request errors", exact: true }),
  ).toBeFocused();
  await expect(picker.getByRole("combobox")).toHaveValue(name);
  page.once("dialog", (d) => d.accept());
  await dialog.getByRole("button", { name: "Cancel", exact: true }).click();
  await expect(dialog).toBeHidden();
});
