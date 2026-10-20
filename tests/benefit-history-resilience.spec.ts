import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";

const path = "**/api/v1/benefits/creation-history?*";
const summary = "Benefit configuration history";
async function openBenefits(page: Page, user = "amelia") {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
  if (user === "amelia") {
    const csrf = (await (await page.request.get("/api/csrf")).json()) as {
      headerName: string;
      token: string;
    };
    const created = await page.request.post("/api/v1/benefits/plans", {
      headers: { [csrf.headerName]: csrf.token },
      data: {
        code: `BD-${Date.now()}`,
        name: "Synthetic health benefit BD",
        currency: "GBP",
        monthlyContribution: "12.50",
        minimumServiceDays: "0",
        effectiveFrom: new Date().toISOString().slice(0, 10),
        reason:
          "Synthetic recovery and long-content fixture " + "界".repeat(250),
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(created.status()).toBe(200);
    await checkResponse(created, "/api/v1/benefits/plans", "post");
  }
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
}

test("benefit history loads on demand and recovers without hiding plans or resetting filters", async ({
  page,
}) => {
  let requests = 0;
  page.on("request", (request) => {
    if (new URL(request.url()).pathname === "/api/v1/benefits/creation-history")
      requests++;
  });
  await openBenefits(page);
  await expect(page.locator(".benefit-card").first()).toBeVisible();
  expect(requests).toBe(0);
  await page.getByLabel("Search benefit plans", { exact: true }).fill("health");
  await expect(page.locator(".benefit-card").first()).toBeVisible();
  await page.route(path, (route) => route.fulfill({ status: 503 }), {
    times: 1,
  });
  await page.getByText(summary, { exact: true }).click();
  const panel = page.getByRole("region", {
    name: "Benefit configuration events",
    exact: true,
  });
  await expect(panel.getByRole("alert")).toBeFocused();
  await expect(page.locator(".benefit-card").first()).toBeVisible();
  await expect(
    page.getByLabel("Search benefit plans", { exact: true }),
  ).toHaveValue("health");
  await panel
    .getByRole("button", { name: "Retry benefit history", exact: true })
    .click();
  await expect(panel.getByRole("heading")).toBeFocused();
  await expect(panel.locator(".history-event").first()).toBeVisible();
  await expect(panel).toContainText("Complete authorised creation history");
  await panel
    .getByRole("button", { name: "Refresh benefit history", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(panel.getByRole("heading")).toBeFocused();
  await expect.poll(() => requests).toBe(3);
  await panel
    .getByText("Creation trace reference", { exact: true })
    .first()
    .click();
  await expect(panel.locator("time").first()).toHaveAttribute("datetime", /Z$/);
  await page.setViewportSize({ width: 390, height: 844 });
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
  await panel.getByRole("heading").scrollIntoViewIfNeeded();
  await page.screenshot({ path: "docs/evidence/benefit-history-mobile.png" });
});

test("benefit history remains usable during a catalogue failure and handles an empty journal separately", async ({
  page,
}) => {
  await page.route(
    "**/api/v1/benefits/plans?*",
    (route) => route.fulfill({ status: 503 }),
    { times: 1 },
  );
  await openBenefits(page);
  await expect(
    page.getByRole("button", { name: "Retry benefit plans", exact: true }),
  ).toBeVisible();
  await page.getByText(summary, { exact: true }).click();
  const panel = page.getByRole("region", {
    name: "Benefit configuration events",
    exact: true,
  });
  await expect(panel.locator(".history-event").first()).toBeVisible();
  await page
    .getByRole("button", { name: "Retry benefit plans", exact: true })
    .click();
  await expect(page.locator(".benefit-card").first()).toBeVisible();
  // The empty response verifies presentation only; real history above is not deleted.
  await page.route(
    path,
    (route) =>
      route.fulfill({
        json: { items: [], total: 0, page: 0, size: 10, throughId: "0" },
      }),
    { times: 1 },
  );
  await page.getByText(summary, { exact: true }).click();
  await expect(panel).toHaveCount(0);
  await page.getByText(summary, { exact: true }).click();
  await expect(panel).toContainText(
    "No matching creation events. Clear the code filter or refresh to include new records.",
  );
  await expect(panel.getByRole("status")).toHaveText(
    "Creation events: 0 · Page 1 · Newest first",
  );
  await panel
    .getByRole("button", { name: "Refresh benefit history", exact: true })
    .click();
  await expect(panel.locator(".history-event").first()).toBeVisible();
});

test("closing benefit history discards a pending read; reopening and role changes clear obsolete history", async ({
  page,
}) => {
  await openBenefits(page);
  await expect(page.locator(".benefit-card").first()).toBeVisible();
  let release!: () => void;
  let entered!: () => void;
  let finished!: () => void;
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  const started = new Promise<void>((resolve) => {
    entered = resolve;
  });
  const completed = new Promise<void>((resolve) => {
    finished = resolve;
  });
  await page.route(
    path,
    async (route) => {
      entered();
      await held;
      await route.fulfill({ status: 503 }).catch(() => {});
      finished();
    },
    { times: 1 },
  );
  try {
    await page.getByText(summary, { exact: true }).click();
    await started;
    await expect(
      page.getByText("Loading benefit history…", { exact: true }),
    ).toBeVisible();
    await page.getByText(summary, { exact: true }).click();
    const panel = page.getByRole("region", {
      name: "Benefit configuration events",
      exact: true,
    });
    await expect(panel).toHaveCount(0);
    await page.getByText(summary, { exact: true }).click();
    await expect(panel.locator(".history-event").first()).toBeVisible();
    release();
    await completed;
    await expect(panel.getByRole("alert")).toHaveCount(0);
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page.getByLabel("Workspace account")).toBeVisible();
    await expect(panel).toHaveCount(0);
    for (const user of ["sophia", "marcus"]) {
      await openBenefits(page, user);
      await expect(
        page.getByRole("heading", { name: "Your benefits", exact: true }),
      ).toBeVisible();
      await expect(page.getByText(summary, { exact: true })).toHaveCount(0);
      expect(
        (
          await page.request.get("/api/v1/benefits/creation-history", {
            headers: { "X-Role": "HR", "X-Tenant-ID": "atlas" },
          })
        ).status(),
      ).toBe(403);
      await page.getByRole("button", { name: "Sign out", exact: true }).click();
      await expect(page.getByLabel("Workspace account")).toBeVisible();
    }
  } finally {
    release();
  }
});
