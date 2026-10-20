import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type {
  CalendarAssignmentReview,
  EmployeeCalendarResult,
} from "@/features/people/employee-calendar-contracts";
import type { Employee } from "@/lib/contracts";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
async function signOut(page: Page) {
  if (await page.getByRole("dialog").count())
    await page
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
}
async function identity(user: string) {
  const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  const t = await (await c.get("/api/csrf")).json();
  expect(
    (
      await c.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [t.headerName]: t.token },
      })
    ).status(),
  ).toBe(204);
  const csrf = await (await c.get("/api/csrf")).json();
  return { c, headers: { [csrf.headerName]: csrf.token } };
}
const day = (n = 0) => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};
const calendar = (code: string) => ({
  calendar: {
    code,
    name: "Assigned company calendar 李",
    timeZone: "Europe/London",
    effectiveFrom: day(),
    effectiveUntil: day(30),
    workingDays: [1, 2, 3, 4, 5, 6, 7],
    closedDates: [day(2)],
  },
  reason: "Synthetic assigned reference",
  idempotencyKey: crypto.randomUUID(),
});
async function openProfile(page: Page) {
  const e = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as Employee;
  await page.getByLabel("Search employees").fill("E003");
  await page
    .getByRole("button", { name: `View ${e.name}`, exact: true })
    .click();
  return e;
}
test("HR assignment is independently reviewed then self previews a dated policy with focused recovery and mobile scope", async ({
  page,
}) => {
  await signIn(page, "amelia");
  const csrf = await (await page.request.get("/api/csrf")).json();
  const headers = { [csrf.headerName]: csrf.token };
  expect(
    (
      await page.request.post("/api/v1/company-calendars", {
        headers,
        data: calendar("AW-UI"),
      })
    ).status(),
  ).toBe(200);
  expect(
    (
      await page.request.post("/api/v1/company-calendars/AW-UI/amendments", {
        headers,
        data: {
          version: 0,
          effectiveDate: day(3),
          workingDays: [1, 2, 3, 4, 5, 6, 7],
          closedDates: [day(3)],
          reason: "Scheduled closure before assignment",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(200);
  const before = await openProfile(page);
  expect(before.calendarCode).toBe("");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.route(
    "**/api/v1/company-calendars?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          title: "Calendar choices temporarily unavailable",
          status: 503,
        }),
      }),
    { times: 1 },
  );
  await page.getByLabel("Information to change").selectOption("calendarCode");
  await expect(
    page.locator(".calendar-picker").getByRole("alert"),
  ).toContainText("We could not complete");
  await page
    .getByRole("button", { name: "Retry calendar choices", exact: true })
    .click();
  await page.getByLabel("Find a calendar").fill("AW-UI");
  await expect(
    page.getByLabel("New value").locator('option[value="AW-UI"]'),
  ).toHaveCount(1);
  await page.getByLabel("New value").selectOption("AW-UI");
  await page
    .getByLabel("Reason for change")
    .fill("Assign company reference without entitlement or pay effect");
  await page.getByLabel("Effective date", { exact: false }).fill(day(31));
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Reason for change")).toHaveValue(
    "Assign company reference without entitlement or pay effect",
  );
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByLabel("New value")).toHaveValue("AW-UI");
  await page.getByLabel("Effective date", { exact: false }).fill(day());
  await page.route(
    "**/api/v1/requests",
    async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      await route.abort("failed");
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  const own = await (await page.request.get("/api/v1/requests")).json();
  expect(
    own.filter(
      (r: { field: string; newValue: string }) =>
        r.field === "calendarCode" && r.newValue === "AW-UI",
    ),
  ).toHaveLength(1);
  const requestId = own.find(
    (r: { field: string; newValue: string }) =>
      r.field === "calendarCode" && r.newValue === "AW-UI",
  ).id as string;
  await signOut(page);
  await signIn(page, "olivia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: "AW-UI" })
    .getByRole("button", { name: "View request" })
    .click();
  await expect(
    page.getByRole("region", { name: "Calendar assignment impact" }),
  ).toContainText("Captured policy revision: 1");
  const review = (await checkResponse(
    await page.request.get(`/api/v1/requests/${requestId}/calendar-context`),
    "/api/v1/requests/{id}/calendar-context",
  )) as CalendarAssignmentReview;
  expect(review.policyChanged).toBe(false);
  await page
    .getByLabel("Decision comment")
    .fill("Independent episode/calendar policy review");
  await page
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "sophia");
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Open your profile", exact: true })
    .click();
  const panel = page.getByRole("region", { name: "Employee company calendar" });
  await expect(panel).toContainText("Current reference: AW-UI");
  await panel.getByLabel("Employee calendar local date").fill(day(3));
  await page.route(
    "**/api/v1/employees/E003/calendar-preview?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          title: "Preview temporarily unavailable",
          status: 503,
        }),
      }),
    { times: 1 },
  );
  await panel
    .getByRole("button", { name: "Preview employee date", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByRole("button", { name: "Retry employee calendar", exact: true })
    .click();
  await expect(panel).toContainText("COMPANY CLOSURE");
  await expect(panel).toContainText("Policy revision 1");
  const result = (await checkResponse(
    await page.request.get(
      `/api/v1/employees/E003/calendar-preview?date=${day(3)}`,
    ),
    "/api/v1/employees/{id}/calendar-preview",
  )) as EmployeeCalendarResult;
  expect(result.preview.working).toBe(false);
  await panel.scrollIntoViewIfNeeded();
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
  await page.screenshot({ path: "docs/evidence/employee-calendar-mobile.png" });
  await panel.getByLabel("Employee calendar local date").fill(day(31));
  await panel
    .getByRole("button", { name: "Preview employee date", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await expect(panel.getByRole("alert")).toContainText("unavailable");
  const employee = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as Employee;
  expect(employee.email).toBe(before.email);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await expect(
    page
      .getByLabel("Information to change")
      .locator('option[value="calendarCode"]'),
  ).toHaveCount(0);
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await signIn(page, "amelia");
  await openProfile(page);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("calendarCode");
  await page
    .getByRole("checkbox", {
      name: "Unassign the current calendar",
      exact: true,
    })
    .check();
  await expect(page.getByLabel("New value")).toBeDisabled();
  await page
    .getByLabel("Reason for change")
    .fill("Explicit company calendar unassignment");
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  const pending = await (await page.request.get("/api/v1/requests")).json();
  const clear = pending.find(
    (r: { field: string; newValue: string; status: string }) =>
      r.field === "calendarCode" &&
      r.newValue === "" &&
      r.status === "SUBMITTED",
  );
  expect(clear).toBeTruthy();
  const reviewer = await identity("olivia");
  try {
    expect(
      (
        await reviewer.c.post(`/api/v1/requests/${clear.id}/decision`, {
          headers: reviewer.headers,
          data: {
            decision: "APPROVED",
            comment: "Independent explicit unassignment",
          },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await reviewer.c.dispose();
  }
  expect(
    (await (await page.request.get("/api/v1/employees/E003")).json())
      .calendarCode,
  ).toBe("");
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await page.getByLabel("Calendar code or name").fill("AW-UI");
  await page
    .getByRole("button", { name: "Search calendars", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Inspect calendar · AW-UI", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "Approved assignment history freezes this calendar",
  );
  await expect(
    page.getByRole("button", { name: "Amend working policy", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Retire calendar", exact: true }),
  ).toHaveCount(0);
});
test("assignment APIs enforce HR-only commands, policy snapshots, unassignment, replay and concurrent policy arbitration", async () => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  const actors = [hr, reviewer, self, manager, outside];
  try {
    for (const code of ["AW-API", "AW-RACE", "AW-RETIRED"])
      expect(
        (
          await hr.c.post("/api/v1/company-calendars", {
            headers: hr.headers,
            data: calendar(code),
          })
        ).status(),
      ).toBe(200);
    const profile = async () =>
      (await (await hr.c.get("/api/v1/employees/E003")).json()) as Employee;
    const command = async (code: string, date = day()) => ({
      employeeId: "E003",
      field: "calendarCode",
      newValue: code,
      effectiveDate: date,
      reason: "Synthetic approved company calendar",
      version: (await profile()).version,
      idempotencyKey: crypto.randomUUID(),
    });
    const approve = (id: string) =>
      reviewer.c.post(`/api/v1/requests/${id}/decision`, {
        headers: reviewer.headers,
        data: {
          decision: "APPROVED",
          comment: "Independent policy/episode review",
        },
      });
    const reject = (id: string) =>
      reviewer.c.post(`/api/v1/requests/${id}/decision`, {
        headers: reviewer.headers,
        data: {
          decision: "REJECTED",
          comment: "Policy changed; submit a fresh proposal",
        },
      });
    const data = await command("AW-API");
    for (const a of [self, manager, outside])
      expect(
        (
          await a.c.post("/api/v1/requests", {
            headers: { ...a.headers, "X-Role": "HR", "X-Tenant": "atlas" },
            data,
          })
        ).status(),
      ).toBe(403);
    for (const [code, date] of [
      ["missing", day()],
      ["MISSING", day()],
      ["AW-API", day(31)],
    ])
      expect(
        (
          await hr.c.post("/api/v1/requests", {
            headers: hr.headers,
            data: {
              ...data,
              newValue: code,
              effectiveDate: date,
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(400);
    const made = await Promise.all(
      [1, 2].map(() =>
        hr.c.post("/api/v1/requests", { headers: hr.headers, data }),
      ),
    );
    expect(made.map((r) => r.status())).toEqual([200, 200]);
    const r = await made[0].json();
    expect((await made[1].json()).id).toBe(r.id);
    for (const a of [hr, self, manager])
      expect(
        (
          await a.c.post(`/api/v1/requests/${r.id}/decision`, {
            headers: a.headers,
            data: { decision: "APPROVED", comment: "Forbidden review" },
          })
        ).status(),
      ).toBe(403);
    for (const a of [self, manager, outside])
      expect(
        (await a.c.get(`/api/v1/requests/${r.id}/calendar-context`)).status(),
      ).toBe(403);
    const results = await Promise.all([approve(r.id), approve(r.id)]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    for (const a of [self, manager]) {
      const p = await a.c.get(
        `/api/v1/employees/E003/calendar-preview?date=${day(2)}`,
      );
      expect(p.status()).toBe(200);
      expect((await p.json()).preview.classification).toBe("COMPANY_CLOSURE");
    }
    for (const [a, id] of [
      [outside, "E003"],
      [self, "E004"],
    ] as const)
      expect(
        (
          await a.c.get(
            `/api/v1/employees/${id}/calendar-preview?date=${day()}`,
          )
        ).status(),
      ).toBe(403);
    const context = await (
      await hr.c.get("/api/v1/company-calendars/AW-API/amendments")
    ).json();
    expect(context).toMatchObject({
      assignmentRecorded: true,
      draft: null,
      retirementDraft: null,
    });
    expect(
      (
        await hr.c.post("/api/v1/company-calendars/AW-API/retirement", {
          headers: hr.headers,
          data: {
            version: 0,
            effectiveDate: day(3),
            reason: "Blocked assigned retirement",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(409);
    const clear = await (
      await hr.c.post("/api/v1/requests", {
        headers: hr.headers,
        data: await command("", day(1)),
      })
    ).json();
    expect((await approve(clear.id)).status()).toBe(200);
    expect((await profile()).calendarCode).toBe("AW-API");
    expect(
      (
        await self.c.get(
          `/api/v1/employees/E003/calendar-preview?date=${day(1)}`,
        )
      ).status(),
    ).toBe(409);
    expect(
      (
        await (
          await hr.c.post("/api/v1/requests", { headers: hr.headers, data })
        ).json()
      ).id,
    ).toBe(r.id);
    expect(
      (
        await hr.c.post("/api/v1/requests", {
          headers: hr.headers,
          data: { ...data, newValue: "AW-RACE" },
        })
      ).status(),
    ).toBe(409);
    const pending = await (
      await hr.c.post("/api/v1/requests", {
        headers: hr.headers,
        data: await command("AW-RACE", day(2)),
      })
    ).json();
    const amend = {
      version: 0,
      effectiveDate: day(1),
      workingDays: [1, 2, 3, 4, 5, 6, 7],
      closedDates: [],
      reason: "Concurrent policy replacement",
      idempotencyKey: crypto.randomUUID(),
    };
    const race = await Promise.all([
      approve(pending.id),
      hr.c.post("/api/v1/company-calendars/AW-RACE/amendments", {
        headers: hr.headers,
        data: amend,
      }),
    ]);
    expect(race.map((r) => r.status()).sort()).toEqual([200, 409]);
    if (race[0].status() === 409) {
      const review = await (
        await hr.c.get(`/api/v1/requests/${pending.id}/calendar-context`)
      ).json();
      expect(review.policyChanged).toBe(true);
      expect((await reject(pending.id)).status()).toBe(200);
    }
    const retiredPending = await (
      await hr.c.post("/api/v1/requests", {
        headers: hr.headers,
        data: await command("AW-RETIRED", day(3)),
      })
    ).json();
    expect(
      (
        await hr.c.post("/api/v1/company-calendars/AW-RETIRED/retirement", {
          headers: hr.headers,
          data: {
            version: 0,
            effectiveDate: day(4),
            reason: "Retire before assignment review",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    expect((await approve(retiredPending.id)).status()).toBe(409);
    expect((await reject(retiredPending.id)).status()).toBe(200);
  } finally {
    for (const a of actors) await a.c.dispose();
  }
});
