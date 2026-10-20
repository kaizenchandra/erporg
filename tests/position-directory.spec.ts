import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse, schemaValidator } from "./support/contract";
import type { PositionDirectoryPage } from "../features/people/position-catalogue";
const path = "/api/v1/organisation/position-directory";
async function identity(user: string) {
  const client = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  let csrf = (await (await client.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  expect(
    (
      await client.post("/api/login", {
        headers: { [csrf.headerName]: csrf.token },
        form: { username: user, password: "atlas-demo" },
      })
    ).status(),
  ).toBe(204);
  csrf = (await (await client.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  return { client, headers: { [csrf.headerName]: csrf.token } };
}
async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
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
  return page.getByRole("region", { name: "Position catalogue", exact: true });
}
async function fixture(count: number) {
  const hr = await identity("amelia");
  const prefix = `BI-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
  const date = (
    (await (await hr.client.get(path)).json()) as PositionDirectoryPage
  ).evaluatedOn;
  const future = new Date(`${date}T00:00:00Z`);
  future.setUTCDate(future.getUTCDate() + 1);
  const tomorrow = future.toISOString().slice(0, 10);
  try {
    for (const suffix of ["A", "B"])
      expect(
        (
          await hr.client.post("/api/v1/organisation/departments", {
            headers: hr.headers,
            data: {
              code: `${prefix}-${suffix}`,
              name: `${prefix} department ${suffix}`,
              costCentre: "DEMO-BI",
              parentCode: null,
              effectiveFrom: date,
              reason: "Synthetic position directory fixture",
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(200);
    for (let i = 0; i < count; i++)
      expect(
        (
          await hr.client.post("/api/v1/organisation/positions", {
            headers: hr.headers,
            data: {
              code: `${prefix}-P${String(i).padStart(2, "0")}`,
              title: `${prefix} role ${String(i).padStart(2, "0")} %_`,
              departmentCode: `${prefix}-${i === count - 1 ? "B" : "A"}`,
              effectiveFrom: i % 2 ? tomorrow : date,
              reason: "Synthetic position timing fixture",
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(200);
    return { prefix, date, tomorrow };
  } finally {
    await hr.client.dispose();
  }
}

test("server-dated current and planned positions paginate, refresh and include a real created reference", async ({
  page,
}) => {
  const { prefix, date, tomorrow } = await fixture(12);
  const panel = await login(page);
  await panel.getByLabel("Search positions", { exact: true }).fill(prefix);
  await expect(panel.locator(".position-card")).toHaveCount(8);
  await expect(
    panel.getByRole("status").filter({ hasText: "matching positions" }),
  ).toContainText(
    `1–8 of 12 matching positions · Page 1 of 2 · Evaluated ${date} (UTC)`,
  );
  await panel
    .getByRole("button", { name: "Next positions", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(
    panel.getByRole("heading", { name: "Position catalogue", exact: true }),
  ).toBeFocused();
  await expect(panel.locator(".position-card")).toHaveCount(4);
  await panel
    .getByRole("combobox", { name: /^Position timing/ })
    .selectOption("PLANNED");
  await expect(panel.locator(".position-card")).toHaveCount(6);
  await expect(
    panel.getByRole("status").filter({ hasText: "matching positions" }),
  ).toContainText("Page 1 of 1");
  await expect(panel.locator(".position-card").first()).toContainText(
    "Planned",
  );
  await panel
    .getByRole("combobox", { name: /^Positions per page/ })
    .selectOption("20");
  await panel
    .getByRole("combobox", { name: /^Position title order/ })
    .selectOption("desc");
  await expect(panel.locator(".position-card").first()).toContainText(
    `${prefix}-P11`,
  );
  await panel
    .getByRole("button", { name: "Refresh positions", exact: true })
    .click();
  await expect(
    panel.getByRole("heading", { name: "Position catalogue", exact: true }),
  ).toBeFocused();
  await expect(
    panel.getByRole("combobox", { name: /^Position timing/ }),
  ).toHaveValue("PLANNED");
  await panel
    .getByRole("button", { name: "Create position", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByRole("textbox", { name: /^Position code/ })
    .fill(`${prefix}-UI`);
  await dialog
    .getByRole("textbox", { name: /^Position title/ })
    .fill(
      `${prefix} Long planned position with a detailed synthetic enterprise title`,
    );
  await dialog
    .getByLabel("Find a department", { exact: true })
    .fill(`${prefix}-A`);
  await dialog
    .getByRole("combobox", { name: /^Department for position/ })
    .selectOption(`${prefix}-A`);
  await dialog
    .getByLabel("Position start date", { exact: false })
    .fill(tomorrow);
  await dialog
    .getByRole("textbox", { name: /^Position configuration reason/ })
    .fill("Synthetic BI planned position through the actual form");
  await dialog
    .getByRole("button", { name: "Save position", exact: true })
    .click();
  await expect(dialog).toHaveCount(0);
  await expect(
    panel
      .getByRole("status")
      .filter({ hasText: "matching positions" })
      .filter({ hasText: "matching positions" }),
  ).toContainText("7 matching positions");
  await expect(
    panel.locator(".position-card").filter({ hasText: `${prefix}-UI` }),
  ).toContainText("Planned");
  await panel
    .getByText("Position configuration history", { exact: true })
    .click();
  await expect(
    panel.locator(".history-event").filter({ hasText: `${prefix}-UI` }),
  ).toContainText("Synthetic BI planned position through the actual form");
  let historyReads = 0;
  page.on("request", (r) => {
    if (r.url().includes("/organisation/reference-history?kind=POSITION"))
      historyReads++;
  });
  await panel
    .getByRole("button", { name: "Refresh positions", exact: true })
    .click();
  await expect(
    panel.getByRole("heading", { name: "Position catalogue", exact: true }),
  ).toBeFocused();
  expect(historyReads).toBe(0);
  await panel
    .getByRole("combobox", { name: /^Position timing/ })
    .selectOption("CURRENT");
  await expect(panel.locator(".position-card")).toHaveCount(6);
  await panel
    .getByRole("combobox", { name: /^Position timing/ })
    .selectOption("PLANNED");
  await expect(panel.locator(".position-card")).toHaveCount(7);
  await page.setViewportSize({ width: 390, height: 844 });
  await panel
    .getByRole("heading", { name: "Position catalogue", exact: true })
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
  await page.screenshot({
    path: "docs/evidence/position-directory-mobile.png",
  });
  await panel
    .locator(".position-card")
    .filter({ hasText: `${prefix}-UI` })
    .scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/position-directory-long-mobile.png",
  });
});

test("position directory API validates timing, contracts and bounds while ignoring forged tenant hints", async () => {
  const { prefix, date } = await fixture(3);
  const self = await identity("sophia"),
    other = await identity("outsider"),
    manager = await identity("marcus");
  try {
    const response = await self.client.get(`${path}?search=${prefix}&size=1`);
    const result = (await checkResponse(
      response,
      path,
    )) as PositionDirectoryPage;
    expect(result).toMatchObject({
      total: 3,
      page: 0,
      size: 1,
      evaluatedOn: date,
    });
    expect(result.items).toHaveLength(1);
    const current = (await checkResponse(
      await manager.client.get(`${path}?search=${prefix}&timing=CURRENT`),
      path,
    )) as PositionDirectoryPage;
    expect(current.total).toBe(2);
    expect(current.items.every((p) => p.timing === "CURRENT")).toBe(true);
    const planned = (await checkResponse(
      await self.client.get(`${path}?search=${prefix}&timing=PLANNED`),
      path,
    )) as PositionDirectoryPage;
    expect(planned.total).toBe(1);
    expect(planned.items[0].timing).toBe("PLANNED");
    const byDepartment = (await checkResponse(
      await self.client.get(
        `${path}?search=${prefix}&departmentCode=${prefix}-B`,
      ),
      path,
    )) as PositionDirectoryPage;
    expect(byDepartment.total).toBe(1);
    expect(byDepartment.items[0].departmentName).toBe(`${prefix} department B`);
    const literal = (await checkResponse(
      await self.client.get(
        `${path}?search=${encodeURIComponent(`${prefix} role 00 %_`)}`,
      ),
      path,
    )) as PositionDirectoryPage;
    expect(literal.total).toBe(1);
    const outside = (await checkResponse(
      await other.client.get(`${path}?search=${prefix}&tenant=atlas&role=HR`),
      path,
    )) as PositionDirectoryPage;
    expect(outside.total).toBe(0);
    for (const query of [
      "page=-1",
      "page=100001",
      "size=0",
      "size=51",
      "timing=ACTIVE",
      "direction=bad",
      "departmentCode=bad%20code",
      `search=${"x".repeat(121)}`,
    ]) {
      const invalid = await self.client.get(`${path}?${query}`);
      expect(invalid.status()).toBe(400);
      await checkResponse(invalid, path);
    }
    const validate = schemaValidator("PositionDirectoryPage");
    expect(
      validate({
        ...result,
        items: [{ ...result.items[0], timing: "ACTIVE" }],
      }),
    ).toBe(false);
    expect(validate({ ...result, evaluatedOn: "not-a-date" })).toBe(false);
    expect(validate({ ...result, size: 51 })).toBe(false);
    expect(
      validate({
        ...result,
        items: [{ ...result.items[0], employeeCount: 12 }],
      }),
    ).toBe(false);
    const legacy = (await checkResponse(
      await self.client.get(`/api/v1/organisation/positions?search=${prefix}`),
      "/api/v1/organisation/positions",
    )) as { total: number };
    expect(legacy.total).toBe(3);
  } finally {
    await Promise.all([
      self.client.dispose(),
      other.client.dispose(),
      manager.client.dispose(),
    ]);
  }
  const anonymous = await request.newContext({
    baseURL: "http://127.0.0.1:3000",
  });
  try {
    expect((await anonymous.get(path)).status()).toBe(401);
  } finally {
    await anonymous.dispose();
  }
});

test("position directory isolates retry, stale responses, empty filters and history state", async ({
  page,
}) => {
  await page.route(
    `**${path}?*`,
    (r) =>
      r.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          title: "Synthetic position outage",
          status: 503,
        }),
      }),
    { times: 1 },
  );
  const panel = await login(page);
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByRole("button", { name: "Retry positions", exact: true })
    .click();
  await expect(
    panel.getByRole("heading", { name: "Position catalogue", exact: true }),
  ).toBeFocused();
  await panel
    .getByText("Position configuration history", { exact: true })
    .click();
  await expect(
    panel.getByRole("region", {
      name: "Position configuration events",
      exact: true,
    }),
  ).toBeVisible();
  let release: () => void = () => {};
  const held = new Promise<void>((r) => {
    release = r;
  });
  let started: () => void = () => {};
  const waiting = new Promise<void>((r) => {
    started = r;
  });
  await page.route(
    `**${path}?*`,
    async (r) => {
      started();
      await held;
      await r
        .fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({
            title: "Obsolete position result",
            status: 503,
          }),
        })
        .catch(() => {});
    },
    { times: 1 },
  );
  await panel
    .getByRole("button", { name: "Refresh positions", exact: true })
    .click();
  await waiting;
  await panel
    .getByRole("combobox", { name: /^Position timing/ })
    .selectOption("PLANNED");
  await expect(panel.locator(".position-card").first()).toContainText(
    "Planned",
  );
  release();
  await expect(panel.getByRole("alert")).toHaveCount(0);
  await expect(
    panel.getByRole("region", {
      name: "Position configuration events",
      exact: true,
    }),
  ).toBeVisible();
  await panel
    .getByLabel("Search positions", { exact: true })
    .fill(`Missing-${crypto.randomUUID()}`);
  await expect(
    panel.getByText(/No positions match these filters/),
  ).toBeVisible();
  await expect(
    panel.getByRole("status").filter({ hasText: "matching positions" }),
  ).toContainText("0–0 of 0 matching positions");
  await expect(
    panel.getByRole("button", { name: "Next positions", exact: true }),
  ).toBeDisabled();
  await expect(
    panel.getByRole("button", { name: "Previous positions", exact: true }),
  ).toBeDisabled();
});
