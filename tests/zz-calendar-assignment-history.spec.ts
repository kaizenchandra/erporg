import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { EmployeeCalendarHistoryResult } from "@/features/people/employee-calendar-contracts";

const template = "/api/v1/employees/{id}/calendar-history";
const day = (n = 0) => {
  const value = new Date();
  value.setUTCDate(value.getUTCDate() + n);
  return value.toISOString().slice(0, 10);
};
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
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
test("approved history is paginated, private and recoverable in the mobile employee profile", async ({
  page,
}) => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia");
  try {
    expect(
      (
        await hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: {
            calendar: {
              code: "AX-HISTORY",
              name: "Long synthetic company calendar 李 ".repeat(3).trim(),
              timeZone: "Europe/London",
              effectiveFrom: day(),
              effectiveUntil: day(45),
              workingDays: [1, 2, 3, 4, 5],
              closedDates: [],
            },
            reason: "Configuration reason must stay private to HR",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    for (let i = 0; i < 10; i++) {
      const e = await (await hr.c.get("/api/v1/employees/E003")).json();
      const created = await hr.c.post("/api/v1/requests", {
        headers: hr.headers,
        data: {
          employeeId: "E003",
          field: "calendarCode",
          newValue: i % 2 === 0 ? "AX-HISTORY" : "",
          effectiveDate: day(20 + i),
          reason: "Private assignment decision fixture",
          version: e.version,
          idempotencyKey: crypto.randomUUID(),
        },
      });
      expect(created.status()).toBe(200);
      const row = await created.json();
      expect(
        (
          await reviewer.c.post(`/api/v1/requests/${row.id}/decision`, {
            headers: reviewer.headers,
            data: {
              decision: "APPROVED",
              comment: "Private independent review fixture",
            },
          })
        ).status(),
      ).toBe(200);
    }
  } finally {
    await hr.c.dispose();
    await reviewer.c.dispose();
  }
  await login(page, "sophia");
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Open your profile", exact: true })
    .click();
  const history = page.getByRole("region", {
    name: "Employee calendar history",
    exact: true,
  });
  await expect(
    history.getByRole("button", { name: "Show calendar assignment history" }),
  ).toHaveAttribute("aria-expanded", "false");
  await page.route(
    "**/api/v1/employees/E003/calendar-history?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({ title: "Temporarily unavailable", status: 503 }),
      }),
    { times: 1 },
  );
  await history
    .getByRole("button", { name: "Show calendar assignment history" })
    .click();
  await expect(history.getByRole("alert")).toBeFocused();
  await history
    .getByRole("button", { name: "Retry calendar history", exact: true })
    .click();
  await expect(
    history.getByRole("heading", {
      name: "Calendar assignment history",
      exact: true,
    }),
  ).toBeFocused();
  await expect(history.getByRole("listitem")).toHaveCount(8);
  await expect(history).toContainText("Scheduled");
  await expect(history).toContainText("Unassigned");
  await expect(history).toContainText("Recorded");
  await expect(history).not.toContainText(
    "Private assignment decision fixture",
  );
  await expect(history).not.toContainText(
    "Configuration reason must stay private",
  );
  await history
    .getByRole("button", { name: "Next calendar history", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(history.getByRole("status")).toContainText("Page 2");
  await expect(
    history.getByRole("heading", {
      name: "Calendar assignment history",
      exact: true,
    }),
  ).toBeFocused();
  await history
    .getByRole("button", { name: "Previous calendar history", exact: true })
    .click();
  await expect(history.getByRole("status")).toContainText("Page 1");
  await history
    .getByRole("button", { name: "Refresh calendar history", exact: true })
    .click();
  await expect(history.getByRole("listitem")).toHaveCount(8);
  await history.getByRole("heading").scrollIntoViewIfNeeded();
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
    path: "docs/evidence/calendar-assignment-history-mobile.png",
  });
  await history
    .getByRole("button", { name: "Hide calendar assignment history" })
    .click();
  await expect(history.getByRole("list")).toBeHidden();
  await history
    .getByRole("button", { name: "Show calendar assignment history" })
    .click();
  await expect(history.getByRole("listitem")).toHaveCount(8);
});
test("history API validates exact bounded pages and rechecks authenticated record and tenant scope", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outsider = await identity("outsider");
  try {
    const results: EmployeeCalendarHistoryResult[] = [];
    for (const a of [hr, self, manager]) {
      const response = await a.c.get(
        "/api/v1/employees/E003/calendar-history?size=1",
      );
      expect(response.status()).toBe(200);
      results.push(
        (await checkResponse(
          response,
          template,
        )) as EmployeeCalendarHistoryResult,
      );
    }
    expect(results[0]).toEqual(results[1]);
    expect(results[1]).toEqual(results[2]);
    const all: string[] = [];
    for (let page = 0; page < results[0].total; page++) {
      const result = (await checkResponse(
        await self.c.get(
          `/api/v1/employees/E003/calendar-history?size=1&page=${page}`,
        ),
        template,
      )) as EmployeeCalendarHistoryResult;
      expect(result.items).toHaveLength(1);
      all.push(result.items[0].versionId);
      expect(Object.keys(result.items[0]).sort()).toEqual(
        [
          "calendarCode",
          "calendarName",
          "effectiveDate",
          "episodeStart",
          "recordedAt",
          "timeZone",
          "versionId",
        ].sort(),
      );
    }
    expect(new Set(all).size).toBe(results[0].total);
    const beyond = (await checkResponse(
      await self.c.get(
        "/api/v1/employees/E003/calendar-history?size=50&page=100000",
      ),
      template,
    )) as EmployeeCalendarHistoryResult;
    expect(beyond.total).toBe(results[0].total);
    expect(beyond.items).toEqual([]);
    for (const query of [
      "page=-1",
      "page=100001",
      "size=0",
      "size=51",
      "size=1.5",
      "page=1.5",
      "page=2147483648",
    ])
      expect(
        (
          await self.c.get(`/api/v1/employees/E003/calendar-history?${query}`)
        ).status(),
      ).toBe(400);
    for (const [a, id] of [
      [outsider, "E003"],
      [self, "E004"],
      [self, "MISSING"],
    ] as const)
      expect(
        (
          await a.c.get(`/api/v1/employees/${id}/calendar-history`, {
            headers: { "X-Tenant": "atlas", "X-Role": "HR" },
          })
        ).status(),
      ).toBe(403);
    const empty = (await checkResponse(
      await hr.c.get("/api/v1/employees/E004/calendar-history"),
      template,
    )) as EmployeeCalendarHistoryResult;
    expect(empty.total).toBe(0);
    expect(empty.items).toEqual([]);
  } finally {
    for (const a of [hr, self, manager, outsider]) await a.c.dispose();
  }
});
test("closing a pending history request cannot expose its rows in another employee's empty profile", async ({
  page,
}) => {
  await login(page, "amelia");
  const open = async (id: string) => {
    const e = await (await page.request.get(`/api/v1/employees/${id}`)).json();
    await page.getByLabel("Search employees").fill(id);
    await page
      .getByRole("button", { name: `View ${e.name}`, exact: true })
      .click();
  };
  await open("E003");
  let release = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  let completed = () => {};
  const finished = new Promise<void>((resolve) => {
    completed = resolve;
  });
  let fetched = () => {};
  const requested = new Promise<void>((resolve) => {
    fetched = resolve;
  });
  await page.route(
    "**/api/v1/employees/E003/calendar-history?*",
    async (route) => {
      const response = await route.fetch();
      fetched();
      await held;
      await route.fulfill({ response });
      completed();
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Show calendar assignment history" })
    .click();
  await requested;
  await expect(
    page.getByText("Loading calendar assignment history…", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await open("E004");
  await page
    .getByRole("button", { name: "Show calendar assignment history" })
    .click();
  const history = page.getByRole("region", {
    name: "Employee calendar history",
    exact: true,
  });
  await expect(history).toContainText(
    "No approved calendar assignments are recorded.",
  );
  release();
  await finished;
  await expect(history.getByRole("listitem")).toHaveCount(0);
  await expect(history.getByRole("status")).toContainText(
    "0 approved calendar records",
  );
  await expect(
    history.getByRole("button", {
      name: "Previous calendar history",
      exact: true,
    }),
  ).toBeDisabled();
  await expect(
    history.getByRole("button", { name: "Next calendar history", exact: true }),
  ).toBeDisabled();
});
