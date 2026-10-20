import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import type { ChangeHistoryPage } from "@/features/people/change-history";
import type { Employee } from "@/lib/contracts";
import { checkResponse } from "./support/contract";
const template = "/api/v1/employees/{id}/change-history";
async function identity(user: string) {
  const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  const token = await (await c.get("/api/csrf")).json();
  expect(
    (
      await c.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [token.headerName]: token.token },
      })
    ).status(),
  ).toBe(204);
  const csrf = await (await c.get("/api/csrf")).json();
  return { c, headers: { [csrf.headerName]: csrf.token } };
}
async function read(
  c: Awaited<ReturnType<typeof identity>>["c"],
  query = "",
  id = "E003",
) {
  const response = await c.get(
    `/api/v1/employees/${id}/change-history?${query}`,
  );
  expect(response.status()).toBe(200);
  return (await checkResponse(response, template)) as ChangeHistoryPage;
}
test("audit API reaches old private events beyond100, scopes totals and cursors, and isolates a higher-ID write", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const first = await read(self.c, "size=7");
    expect(first.total).toBeGreaterThan(100);
    expect(first.nextCursor).toBeTruthy();
    const old = (await (
      await self.c.get("/api/v1/employees/E003/history")
    ).json()) as { field: string }[];
    const births = await read(self.c, "field=birthDate&size=50");
    expect(births.items.length).toBeGreaterThan(
      old.filter((h) => h.field === "birthDate").length,
    );
    const e = (await (
      await self.c.get("/api/v1/employees/E003")
    ).json()) as Employee;
    const command = {
      employeeId: "E003",
      field: "phone",
      newValue:
        e.phone === "+44 7700 246810" ? "+44 7700 246811" : "+44 7700 246810",
      effectiveDate: new Date().toISOString().slice(0, 10),
      reason: "Recorded pagination boundary with long Unicode reason 李 "
        .repeat(9)
        .slice(0, 480),
      version: e.version,
      idempotencyKey: crypto.randomUUID(),
    };
    const submitted = await self.c.post("/api/v1/requests", {
      headers: self.headers,
      data: command,
    });
    expect(submitted.status()).toBe(200);
    const change = await submitted.json();
    expect(
      (
        await self.c.post(`/api/v1/requests/${change.id}/withdraw`, {
          headers: self.headers,
          data: {
            reason:
              "Close synthetic pagination boundary proposal without applying a profile change",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    const ids = first.items.map((h) => h.id);
    let cursor = first.nextCursor;
    while (cursor) {
      const page = await read(
        self.c,
        new URLSearchParams({ size: "7", cursor }).toString(),
      );
      expect(page.total).toBe(first.total);
      ids.push(...page.items.map((h) => h.id));
      cursor = page.nextCursor;
    }
    expect(ids.length).toBe(first.total);
    expect(new Set(ids).size).toBe(first.total);
    const refreshed = await read(self.c, "size=50");
    expect(refreshed.total).toBe(first.total + 2);
    expect(ids).not.toContain(refreshed.items[0].id);
    const ascIds: string[] = [];
    cursor = null;
    do {
      const q = new URLSearchParams({ order: "asc", size: "50" });
      if (cursor) q.set("cursor", cursor);
      const page = await read(self.c, q.toString());
      ascIds.push(...page.items.map((h) => h.id));
      cursor = page.nextCursor;
    } while (cursor);
    expect(ascIds.length).toBe(refreshed.total);
    expect(new Set(ascIds).size).toBe(refreshed.total);
    const publicPage = await read(manager.c, "size=50");
    expect(publicPage.total).toBeLessThan(refreshed.total);
    expect(publicPage.fields).not.toContain("birthDate");
    expect(publicPage.fields).not.toContain("legalEntityCode");
    expect(
      publicPage.items.every(
        (h) =>
          !births.fields.includes(h.field) ||
          publicPage.fields.includes(h.field),
      ),
    ).toBe(true);
    for (const query of [
      "size=0",
      "size=51",
      "size=1.5",
      "order=sideways",
      "field=unknown",
      "action=unknown",
      "cursor=x",
      "cursor=" + "x".repeat(257),
    ])
      expect(
        (
          await self.c.get(`/api/v1/employees/E003/change-history?${query}`)
        ).status(),
      ).toBe(400);
    for (const query of [
      new URLSearchParams({
        size: "7",
        cursor: first.nextCursor!,
        field: "phone",
      }),
      new URLSearchParams({ size: "8", cursor: first.nextCursor! }),
    ])
      expect(
        (
          await self.c.get(`/api/v1/employees/E003/change-history?${query}`)
        ).status(),
      ).toBe(400);
    expect(
      (
        await hr.c.get(
          `/api/v1/employees/E003/change-history?${new URLSearchParams({ size: "7", cursor: first.nextCursor! })}`,
        )
      ).status(),
    ).toBe(400);
    for (const [a, id] of [
      [outside, "E003"],
      [self, "E004"],
      [self, "MISSING"],
    ] as const)
      expect(
        (
          await a.c.get(`/api/v1/employees/${id}/change-history`, {
            headers: { "X-Role": "HR", "X-Tenant": "atlas" },
          })
        ).status(),
      ).toBe(403);
    expect(
      (
        await manager.c.get(
          "/api/v1/employees/E003/change-history?field=birthDate",
        )
      ).status(),
    ).toBe(403);
    const empty = await read(hr.c, "field=birthDate&action=WITHDRAWN");
    expect(empty.total).toBe(0);
    expect(empty.items).toEqual([]);
    expect(empty.nextCursor).toBeNull();
  } finally {
    for (const a of [hr, self, manager, outside]) await a.c.dispose();
  }
});
async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("sophia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
}
test("profile history recovers independently, filters older events and cancels stale pages with accessible mobile controls", async ({
  page,
}) => {
  await login(page);
  await page.route(
    "**/api/v1/employees/E003/change-history?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          status: 503,
          title: "History temporarily unavailable",
        }),
      }),
    { times: 1 },
  );
  await page.route(
    "**/api/v1/hiring/requests/employees/E003/employment-history",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          status: 503,
          title: "Employment temporarily unavailable",
        }),
      }),
    { times: 1 },
  );
  await page.getByTitle("Open your profile", { exact: true }).click();
  const panel = page.getByRole("region", {
    name: "Employee change history",
    exact: true,
  });
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByRole("button", { name: "Retry change history", exact: true })
    .click();
  await expect(
    panel.getByRole("heading", { name: "Change history", exact: true }),
  ).toBeFocused();
  await expect(panel.locator(".history-event")).toHaveCount(10);
  await expect(
    page.getByRole("button", { name: "Retry employment history", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Retry employment history", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Retry employment history", exact: true }),
  ).toHaveCount(0);
  await panel
    .getByRole("button", { name: "Next change history", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(panel.getByRole("status")).toContainText("Page 2");
  await expect(panel.getByRole("heading")).toBeFocused();
  await panel
    .getByRole("button", { name: "Previous change history", exact: true })
    .click();
  await expect(panel.getByRole("status")).toContainText("Page 1");
  await panel
    .getByRole("combobox", { name: "History field", exact: true })
    .selectOption("birthDate");
  await expect(panel.locator(".history-event").first()).toContainText(
    "Recorded birth date change",
  );
  await panel
    .getByRole("combobox", { name: "History action", exact: true })
    .selectOption("APPROVED");
  await expect(panel.locator(".history-event").first()).toContainText(
    "approved",
  );
  await panel
    .getByRole("combobox", { name: "History recording order", exact: true })
    .selectOption("asc");
  const expected = (await (
    await page.request.get(
      "/api/v1/employees/E003/change-history?field=birthDate&action=APPROVED&order=asc",
    )
  ).json()) as ChangeHistoryPage;
  await expect(panel.locator("time").first()).toHaveAttribute(
    "datetime",
    expected.items[0].recordedAt,
  );
  await panel
    .getByRole("combobox", { name: "History action", exact: true })
    .selectOption("WITHDRAWN");
  await expect(panel).toContainText("No matching authorised changes.");
  await expect(
    panel.getByRole("button", { name: "Next change history", exact: true }),
  ).toBeDisabled();
  await panel
    .getByRole("combobox", { name: "History action", exact: true })
    .selectOption("ALL");
  await panel
    .getByRole("combobox", { name: "History recording order", exact: true })
    .selectOption("desc");
  await panel
    .getByRole("combobox", { name: "History rows per page", exact: true })
    .selectOption("25");
  await panel
    .getByRole("combobox", { name: "History field", exact: true })
    .selectOption("ALL");
  await expect(panel.locator(".history-event")).toHaveCount(25);
  let release = () => {};
  const held = new Promise<void>((r) => {
    release = r;
  });
  let received = () => {};
  const ready = new Promise<void>((r) => {
    received = r;
  });
  let complete = () => {};
  const finished = new Promise<void>((r) => {
    complete = r;
  });
  await page.route(
    "**/api/v1/employees/E003/change-history?field=legalName&*",
    async (route) => {
      const response = await route.fetch();
      received();
      await held;
      await route.fulfill({ response });
      complete();
    },
    { times: 1 },
  );
  await panel
    .getByRole("combobox", { name: "History field", exact: true })
    .selectOption("legalName");
  await ready;
  await expect(panel).toContainText("Loading change history…");
  await panel
    .getByRole("combobox", { name: "History field", exact: true })
    .selectOption("phone");
  await expect(panel.locator(".history-event").first()).toContainText(
    "Contact number change",
  );
  release();
  await finished;
  await expect(
    panel.getByRole("combobox", { name: "History field", exact: true }),
  ).toHaveValue("phone");
  await expect(panel.locator(".history-event").first()).toContainText(
    "Contact number change",
  );
  await panel
    .getByRole("combobox", { name: "History rows per page", exact: true })
    .selectOption("10");
  await panel
    .getByRole("combobox", { name: "History action", exact: true })
    .selectOption("SUBMITTED");
  await expect(panel.locator(".history-event").first()).toContainText(
    "long Unicode reason",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await panel.getByRole("heading").evaluate((element) => {
    element.scrollIntoView({ block: "start" });
    const dialog = element.closest("dialog");
    const header = dialog?.querySelector(".dialog-header");
    if (dialog && header)
      dialog.scrollTop -= header.getBoundingClientRect().height + 12;
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
    path: "docs/evidence/employee-audit-pagination-mobile.png",
  });
  await panel
    .locator(".history-event")
    .first()
    .evaluate((element) => {
      element.scrollIntoView({ block: "start" });
      const dialog = element.closest("dialog");
      const header = dialog?.querySelector(".dialog-header");
      if (dialog && header)
        dialog.scrollTop -= header.getBoundingClientRect().height + 12;
    });
  await page.screenshot({
    path: "docs/evidence/employee-audit-pagination-event-mobile.png",
  });
  await panel
    .getByRole("button", { name: "Refresh change history", exact: true })
    .click();
  await expect(panel.getByRole("heading")).toBeFocused();
  await expect(panel.getByRole("status")).toContainText("Page 1");
});
test("closing a pending audit read cannot replace another employee's history", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
  const open = async (id: string) => {
    const e = (await (
      await page.request.get(`/api/v1/employees/${id}`)
    ).json()) as Employee;
    await page.getByLabel("Search employees").fill(id);
    await page
      .getByRole("button", { name: `View ${e.name}`, exact: true })
      .click();
  };
  let release = () => {};
  const held = new Promise<void>((r) => {
    release = r;
  });
  let received = () => {};
  const ready = new Promise<void>((r) => {
    received = r;
  });
  let complete = () => {};
  const finished = new Promise<void>((r) => {
    complete = r;
  });
  await page.route(
    "**/api/v1/employees/E003/change-history?*",
    async (route) => {
      const response = await route.fetch();
      received();
      await held;
      await route.fulfill({ response });
      complete();
    },
    { times: 1 },
  );
  await open("E003");
  await ready;
  await expect(
    page.getByRole("region", { name: "Employee change history", exact: true }),
  ).toContainText("Loading change history…");
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  const expected = (await (
    await page.request.get("/api/v1/employees/E004/change-history")
  ).json()) as ChangeHistoryPage;
  await open("E004");
  const panel = page.getByRole("region", {
    name: "Employee change history",
    exact: true,
  });
  await expect(panel.getByRole("status")).toHaveText(
    `${expected.total} authorised changes · Page 1 · Newest first`,
  );
  release();
  await finished;
  await expect(panel.getByRole("status")).toHaveText(
    `${expected.total} authorised changes · Page 1 · Newest first`,
  );
  await expect(
    panel.getByRole("combobox", { name: "History field", exact: true }),
  ).toHaveValue("ALL");
});
