import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse, schemaValidator } from "./support/contract";
import type { ReferenceHistoryPage } from "../features/people/reference-history";
async function login(page: Page, user = "amelia") {
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
const path = "/api/v1/organisation/reference-history";
test("both reference journals reach beyond100 and preserve scoped ID boundaries and exact contracts", async ({
  page,
}) => {
  test.setTimeout(60000);
  await login(page);
  const csrf = (await (await page.request.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  const headers = { [csrf.headerName]: csrf.token },
    date = new Date().toISOString().slice(0, 10);
  for (let i = 0; i < 105; i++) {
    const code = `BC-D${String(i).padStart(3, "0")}`;
    expect(
      (
        await page.request.post("/api/v1/organisation/departments", {
          headers,
          data: {
            code,
            name: `Synthetic BC department ${i}`,
            parentCode: null,
            costCentre: "DEMO-BC",
            effectiveFrom: date,
            reason:
              i === 104
                ? "Synthetic long creation reason " + "界".repeat(300)
                : "Synthetic complete reference history",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    expect(
      (
        await page.request.post("/api/v1/organisation/positions", {
          headers,
          data: {
            code: `BC-P${String(i).padStart(3, "0")}`,
            title: `Synthetic BC position ${i}`,
            departmentCode: code,
            effectiveFrom: date,
            reason: "Synthetic complete position history",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
  }
  for (const kind of ["DEPARTMENT", "POSITION"]) {
    const first = (await checkResponse(
      await page.request.get(`${path}?kind=${kind}&size=50`),
      path,
    )) as ReferenceHistoryPage;
    expect(first.total).toBeGreaterThan(100);
    const all = [...first.items];
    for (let pageNo = 1; all.length < first.total; pageNo++) {
      const result = (await (
        await page.request.get(
          `${path}?kind=${kind}&size=50&page=${pageNo}&throughId=${first.throughId}`,
        )
      ).json()) as ReferenceHistoryPage;
      expect(result.total).toBe(first.total);
      expect(result.items.length).toBeGreaterThan(0);
      all.push(...result.items);
    }
    expect(new Set(all.map((i) => i.id)).size).toBe(first.total);
    expect(
      all.filter((i) =>
        i.code.startsWith(kind === "DEPARTMENT" ? "BC-D" : "BC-P"),
      ),
    ).toHaveLength(105);
    const code = kind === "DEPARTMENT" ? "BC-D000" : "BC-P000";
    const filtered = (await (
      await page.request.get(`${path}?kind=${kind}&code=${code}&order=asc`)
    ).json()) as ReferenceHistoryPage;
    expect(filtered.total).toBe(1);
    expect(filtered.items[0].code).toBe(code);
    const legacy = (await (
      await page.request.get(
        kind === "DEPARTMENT"
          ? "/api/v1/organisation/history"
          : "/api/v1/organisation/positions/history",
      )
    ).json()) as { code: string }[];
    expect(legacy.length).toBe(100);
    expect(legacy.some((i) => i.code === code)).toBe(false);
  }
  const initial = (await (
    await page.request.get(path + "?kind=DEPARTMENT&size=1")
  ).json()) as ReferenceHistoryPage;
  expect(
    (
      await page.request.post("/api/v1/organisation/departments", {
        headers,
        data: {
          code: "BC-LATER",
          name: "Synthetic BC later creation",
          parentCode: null,
          costCentre: "DEMO-BC",
          effectiveFrom: date,
          reason: "Higher ID boundary verification",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(200);
  expect(
    (
      (await (
        await page.request.get(
          `${path}?kind=DEPARTMENT&throughId=${initial.throughId}`,
        )
      ).json()) as ReferenceHistoryPage
    ).total,
  ).toBe(initial.total);
  expect(
    (
      (await (
        await page.request.get(path + "?kind=DEPARTMENT")
      ).json()) as ReferenceHistoryPage
    ).total,
  ).toBe(initial.total + 1);
  for (const query of [
    "kind=BAD",
    "kind=DEPARTMENT&size=51",
    "kind=DEPARTMENT&page=1",
    "kind=DEPARTMENT&throughId=01",
    "kind=DEPARTMENT&throughId=9223372036854775808",
    "kind=DEPARTMENT&code=lower",
    "kind=DEPARTMENT&order=unsafe",
  ]) {
    expect((await page.request.get(path + "?" + query)).status()).toBe(400);
  }
  const validate = schemaValidator("ReferenceHistoryPage");
  expect(validate(initial)).toBe(true);
  expect(validate({ ...initial, throughId: 1 })).toBe(false);
  expect(
    validate({ ...initial, items: [{ ...initial.items[0], id: 1 }] }),
  ).toBe(false);
  expect(validate({ ...initial, privateEmployee: "forbidden" })).toBe(false);
  expect(validate({ ...initial, size: 51 })).toBe(false);
  for (const user of ["sophia", "marcus", "outsider"]) {
    const client = await request.newContext({
      baseURL: "http://127.0.0.1:3000",
    });
    try {
      const token = (await (await client.get("/api/csrf")).json()) as {
        headerName: string;
        token: string;
      };
      expect(
        (
          await client.post("/api/login", {
            headers: { [token.headerName]: token.token },
            form: { username: user, password: "atlas-demo" },
          })
        ).status(),
      ).toBe(204);
      for (const kind of ["DEPARTMENT", "POSITION"])
        expect(
          (
            await client.get(
              path + `?kind=${kind}&throughId=${initial.throughId}`,
              { headers: { "X-Tenant-ID": "atlas", "X-Role": "HR" } },
            )
          ).status(),
        ).toBe(403);
    } finally {
      await client.dispose();
    }
  }
});
test("reference history recovers independently, filters old events and cancels closed or stale mobile reads", async ({
  page,
}) => {
  await login(page);
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const directory = page.getByRole("region", {
    name: "Department directory",
    exact: true,
  });
  await directory
    .getByLabel("Search departments", { exact: true })
    .fill("BC-D000");
  await expect(directory).toContainText("Synthetic BC department 0");
  await page.route(
    "**/api/v1/organisation/reference-history?kind=DEPARTMENT&*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({ status: 503, title: "History unavailable" }),
      }),
    { times: 1 },
  );
  const summary = page.getByText("Department configuration history", {
    exact: true,
  });
  await summary.click();
  const panel = page.getByRole("region", {
    name: "Department configuration events",
    exact: true,
  });
  await expect(panel.getByRole("alert")).toBeFocused();
  await expect(directory.locator("article h4")).toHaveText(
    "Synthetic BC department 0",
  );
  await panel
    .getByRole("button", { name: "Retry department history", exact: true })
    .click();
  await expect(panel.getByRole("heading")).toBeFocused();
  await expect(panel.locator(".history-event")).toHaveCount(10);
  await panel
    .getByRole("button", { name: "Next department history", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(panel.getByRole("status")).toContainText("Page 2");
  await panel
    .getByRole("button", { name: "Previous department history", exact: true })
    .click();
  await expect(panel.getByRole("status")).toContainText("Page 1");
  await panel
    .getByLabel("Department history reference code", { exact: true })
    .fill("BC-D000");
  await panel
    .getByRole("button", { name: "Apply department history code", exact: true })
    .click();
  await expect(panel.locator(".history-event strong")).toHaveText(
    "BC-D000 · Created",
  );
  await panel
    .getByRole("combobox", {
      name: "Department history recording order",
      exact: true,
    })
    .selectOption("asc");
  await panel
    .getByRole("combobox", {
      name: "Department history rows per page",
      exact: true,
    })
    .selectOption("25");
  await panel
    .getByLabel("Department history reference code", { exact: true })
    .fill("NOT-FOUND");
  await panel
    .getByRole("button", { name: "Apply department history code", exact: true })
    .click();
  await expect(panel).toContainText("No matching creation events.");
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
    "**/api/v1/organisation/reference-history?kind=DEPARTMENT&code=BC-D000&*",
    async (route) => {
      const response = await route.fetch();
      ready();
      await held;
      await route.fulfill({ response });
      finish();
    },
    { times: 1 },
  );
  await panel
    .getByLabel("Department history reference code", { exact: true })
    .fill("BC-D000");
  await panel
    .getByRole("button", { name: "Apply department history code", exact: true })
    .click();
  await received;
  await summary.click();
  await expect(panel).toBeHidden();
  await summary.click();
  await expect(panel.locator(".history-event")).toHaveCount(10);
  release();
  await done;
  await expect(
    panel.getByLabel("Department history reference code", { exact: true }),
  ).toHaveValue("");
  await panel
    .getByLabel("Department history reference code", { exact: true })
    .fill("BC-D104");
  await panel
    .getByRole("button", { name: "Apply department history code", exact: true })
    .click();
  await expect(panel).toContainText("Synthetic long creation reason");
  await page.setViewportSize({ width: 390, height: 844 });
  await panel.getByRole("heading").evaluate((element) => {
    element.scrollIntoView({ block: "start" });
    window.scrollBy(0, -85);
  });
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
    path: "docs/evidence/reference-history-controls-mobile.png",
  });
  await panel.locator(".history-event").evaluate((element) => {
    element.scrollIntoView({ block: "start" });
    window.scrollBy(0, -85);
  });
  await page.screenshot({
    path: "docs/evidence/reference-history-event-mobile.png",
  });
  await panel
    .getByRole("button", { name: "Refresh department history", exact: true })
    .click();
  await expect(panel.getByRole("heading")).toBeFocused();
  await expect(panel.getByRole("status")).toContainText("Page 1");
  await summary.click();
  await page
    .getByText("Position configuration history", { exact: true })
    .click();
  const positions = page.getByRole("region", {
    name: "Position configuration events",
    exact: true,
  });
  await positions
    .getByLabel("Position history reference code", { exact: true })
    .fill("BC-P000");
  await positions
    .getByRole("button", { name: "Apply position history code", exact: true })
    .click();
  await expect(positions.locator(".history-event strong")).toHaveText(
    "BC-P000 · Created",
  );
});
