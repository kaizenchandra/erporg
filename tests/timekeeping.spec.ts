import {
  test,
  expect,
  request,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse, schemaValidator } from "./support/contract";
import type {
  Sheet,
  Shift,
  Page as TimePage,
  SheetContext,
} from "../features/timekeeping/contracts";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
}
async function signOut(page: Page) {
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
}
async function csrf(client: APIRequestContext) {
  const c = (await (await client.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  return { [c.headerName]: c.token };
}
async function client(user: string) {
  const c = await request.newContext({ baseURL: origin });
  expect(
    (
      await c.post("/api/login", {
        headers: await csrf(c),
        form: { username: user, password: "atlas-demo" },
      })
    ).status(),
  ).toBe(204);
  return c;
}
function weekAgo(n: number) {
  const d = new Date();
  d.setUTCHours(0, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7) - 7 * n);
  return d.toISOString().slice(0, 10);
}
function entries(week: string, minutes = 60) {
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(`${week}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + i);
    return {
      date: d.toISOString().slice(0, 10),
      minutes: i === 0 ? minutes : 0,
      code: i === 0 ? "DEMO-TIME" : "",
    };
  });
}
async function timeNav(page: Page) {
  await page
    .getByRole("button", { name: "Time & attendance", exact: true })
    .click();
}

test("attendance clocking, duplicate detection, missed-punch correction, history and mobile access", async ({
  page,
}) => {
  await signIn(page, "sophia");
  await timeNav(page);
  await expect(
    page.getByRole("heading", { name: "Attendance", exact: true }),
  ).toBeVisible();
  const prior = (await (
    await page.request.get("/api/v1/time/attendance/E003?size=50")
  ).json()) as TimePage<Shift>;
  const openShift = prior.items.find((s) => !s.end);
  if (openShift)
    expect(
      (
        await page.request.post("/api/v1/time/punches/OUT", {
          headers: await csrf(page.request),
          data: { zone: openShift.zone, idempotencyKey: crypto.randomUUID() },
        })
      ).status(),
    ).toBe(200);
  const clock = page.waitForResponse((r) =>
    r.url().endsWith("/api/v1/time/punches/IN"),
  );
  await page.getByRole("button", { name: "Clock in", exact: true }).click();
  const shifted = (await checkResponse(
    await clock,
    "/api/v1/time/punches/{action}",
    "post",
  )) as Shift;
  await expect(
    page.getByText("Missing clock-out · open shift", { exact: true }),
  ).toBeVisible();
  const duplicate = page.waitForResponse((r) =>
    r.url().endsWith("/api/v1/time/punches/IN"),
  );
  await page.getByRole("button", { name: "Clock in", exact: true }).click();
  expect((await duplicate).status()).toBe(409);
  await expect(
    page.getByRole("alert", { name: "Time workflow errors", exact: true }),
  ).toBeFocused();
  await expect(
    page.getByRole("alert", { name: "Time workflow errors", exact: true }),
  ).toContainText("already open");
  await page.getByRole("button", { name: "Clock out", exact: true }).click();
  await expect(
    page.getByText("Clock-out recorded.", { exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Record missed shift", exact: true })
    .click();
  const start = new Date();
  start.setUTCDate(start.getUTCDate() - 2);
  start.setUTCHours(6, 0, 0, 0);
  const end = new Date(start.getTime() + 3600000);
  await page
    .getByLabel("Shift start", { exact: false })
    .fill(start.toISOString());
  await page
    .getByLabel("Shift end", { exact: false })
    .fill(start.toISOString());
  await page
    .getByLabel("Correction reason")
    .fill("Synthetic missed entry recovery");
  await page
    .getByRole("button", { name: "Save attendance correction", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Time workflow errors", exact: true }),
  ).toContainText("End must follow start");
  await expect(page.getByLabel("Correction reason")).toHaveValue(
    "Synthetic missed entry recovery",
  );
  await page.getByLabel("Shift end", { exact: false }).fill(end.toISOString());
  await page
    .getByRole("button", { name: "Save attendance correction", exact: true })
    .click();
  await expect(
    page.getByText("Attendance correction recorded with history.", {
      exact: true,
    }),
  ).toBeVisible();
  await page
    .locator(".time-record")
    .filter({ hasText: "Synthetic missed entry recovery" })
    .getByRole("button", { name: "Correct shift", exact: true })
    .click();
  await page
    .getByLabel("Shift end", { exact: false })
    .fill(new Date(end.getTime() + 1800000).toISOString());
  await page
    .getByLabel("Correction reason")
    .fill("Synthetic corrected clock-out after lost response");
  await page.route(
    "**/api/v1/time/corrections",
    async (route) => {
      const actual = await route.fetch();
      expect(actual.status()).toBe(200);
      await route.abort("failed");
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Save attendance correction", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Time workflow errors", exact: true }),
  ).toBeFocused();
  await expect(page.getByLabel("Correction reason")).toHaveValue(
    "Synthetic corrected clock-out after lost response",
  );
  await page
    .getByRole("button", { name: "Save attendance correction", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await page
    .getByLabel("Attendance time zone", { exact: true })
    .fill("Europe/London");
  await page.getByRole("button", { name: "Clock in", exact: true }).click();
  const londonOut = page.waitForResponse((r) =>
    r.url().endsWith("/api/v1/time/punches/OUT"),
  );
  await page.getByRole("button", { name: "Clock out", exact: true }).click();
  expect((await londonOut).status()).toBe(200);
  const london = page
    .locator(".time-record")
    .filter({ hasText: "Europe/London" })
    .first();
  await london
    .getByRole("button", { name: "Correct shift", exact: true })
    .click();
  await expect(page.getByLabel("Shift start", { exact: false })).toHaveValue(
    /\+[0-9]{2}:[0-9]{2}$/,
  );
  await page
    .getByLabel("Correction reason")
    .fill("Offset-preserving attendance correction");
  await page
    .getByRole("button", { name: "Save attendance correction", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  const history = await page.request.get("/api/v1/time/history/E003");
  await checkResponse(history, "/api/v1/time/history/{employeeId}");
  expect(await history.text()).toContain("Synthetic missed entry recovery");
  const shifts = (await (
    await page.request.get("/api/v1/time/attendance/E003")
  ).json()) as TimePage<Shift>;
  expect(shifts.items.some((s) => s.id === shifted.id && s.end)).toBe(true);
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
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "docs/evidence/timekeeping-attendance-mobile.png",
    fullPage: true,
  });
});

test("weekly draft to rejection, revision, independent approval and actual reconciled costing", async ({
  page,
}) => {
  const week = weekAgo(10 + Math.floor(Math.random() * 40));
  await signIn(page, "sophia");
  await timeNav(page);
  await page
    .getByRole("button", { name: "My timesheets", exact: true })
    .click();
  await page
    .getByRole("button", { name: "New timesheet", exact: true })
    .click();
  await page.getByLabel("Week starting Monday").fill(week);
  await page
    .getByLabel(`Working minutes for ${week}`, { exact: true })
    .fill("0.5");
  await page
    .getByLabel(`Activity code for ${week}`, { exact: true })
    .fill("DEMO-TIME");
  await page
    .getByLabel("Timesheet reason")
    .fill("Synthetic weekly time acceptance");
  await page
    .getByRole("button", { name: "Save timesheet draft", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Time workflow errors", exact: true }),
  ).toContainText("whole minutes");
  await expect(page.getByLabel("Timesheet reason")).toHaveValue(
    "Synthetic weekly time acceptance",
  );
  await page
    .getByLabel(`Working minutes for ${week}`, { exact: true })
    .fill("480");
  await page
    .getByRole("button", { name: "Save timesheet draft", exact: true })
    .click();
  await expect(
    page.getByText("Timesheet draft saved. Open it to submit for approval.", {
      exact: true,
    }),
  ).toBeVisible();
  async function open() {
    await page
      .getByRole("button", {
        name: `Open timesheet for ${week} · E003`,
        exact: true,
      })
      .click();
    await expect(
      page.getByRole("heading", {
        name: "Timesheet details and costing",
        exact: true,
      }),
    ).toBeVisible();
  }
  async function submit() {
    await page
      .getByLabel("Timesheet comment")
      .fill("Please review actual working time");
    await page
      .getByLabel("I have checked this timesheet and its impact.", {
        exact: true,
      })
      .check();
    await page
      .getByRole("button", {
        name: "Submit timesheet for approval",
        exact: true,
      })
      .click();
    await expect(
      page.getByText("Timesheet submitted.", { exact: true }),
    ).toBeVisible();
  }
  async function reviewer() {
    await signOut(page);
    await signIn(page, "marcus");
    await page.getByRole("button", { name: /^Approvals/ }).click();
    await page.getByRole("button", { name: "Timesheets", exact: true }).click();
    await open();
  }
  await open();
  await submit();
  await reviewer();
  await page.getByLabel("Timesheet decision").selectOption("REJECT");
  await page
    .getByLabel("Timesheet comment")
    .fill("Please correct Wednesday allocation");
  await page
    .getByLabel("I have checked this timesheet and its impact.", {
      exact: true,
    })
    .check();
  await page
    .getByRole("button", { name: "Confirm timesheet decision", exact: true })
    .click();
  await expect(
    page.getByText("Timesheet rejected.", { exact: true }),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "sophia");
  await timeNav(page);
  await page
    .getByRole("button", { name: "My timesheets", exact: true })
    .click();
  await open();
  await page
    .getByRole("button", { name: "Edit draft hours", exact: true })
    .click();
  await page
    .getByLabel(`Working minutes for ${week}`, { exact: true })
    .fill("420");
  await page
    .getByLabel("Timesheet reason")
    .fill("Corrected actual working hours");
  await page
    .getByRole("button", { name: "Save timesheet draft", exact: true })
    .click();
  await expect(
    page.getByText("Timesheet draft saved. Open it to submit for approval.", {
      exact: true,
    }),
  ).toBeVisible();
  await open();
  await submit();
  await reviewer();
  await page
    .getByRole("button", { name: "Attendance and audit evidence", exact: true })
    .click();
  await expect(
    page.getByRole("region", { name: "Time audit history", exact: true }),
  ).toBeVisible();
  await page
    .getByLabel("Timesheet comment")
    .fill("Actual hours independently reconciled");
  await page
    .getByLabel("I have checked this timesheet and its impact.", {
      exact: true,
    })
    .check();
  const approved = page.waitForResponse((r) =>
    /\/api\/v1\/time\/sheets\/[^/]+\/actions$/.test(r.url()),
  );
  await page
    .getByRole("button", { name: "Confirm timesheet decision", exact: true })
    .click();
  const response = await approved;
  const sheet = (await checkResponse(
    response,
    "/api/v1/time/sheets/{id}/actions",
    "post",
  )) as Sheet;
  expect(sheet.status).toBe("APPROVED");
  await expect(
    page.getByText("Timesheet approved.", { exact: true }),
  ).toBeVisible();
  const replay = await page.request.post(
    `/api/v1/time/sheets/${sheet.id}/actions`,
    {
      headers: await csrf(page.request),
      data: response.request().postDataJSON() as Record<string, unknown>,
    },
  );
  expect(replay.status()).toBe(200);
  const context = (await checkResponse(
    await page.request.get(`/api/v1/time/sheets/${sheet.id}`),
    "/api/v1/time/sheets/{id}",
  )) as SheetContext;
  expect(context.allocatedMinutes).toBe(420);
  expect(context.allocations).toHaveLength(1);
  await page.getByLabel("Timesheet status").selectOption("APPROVED");
  await open();
  await expect(
    page.getByText("Reconciled: allocated minutes equal approved minutes.", {
      exact: false,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Edit draft hours", exact: true }),
  ).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  expect(
    await page
      .getByRole("dialog")
      .evaluate((el) => el.scrollWidth <= el.clientWidth),
  ).toBe(true);
  await page.screenshot({
    path: "docs/evidence/timekeeping-approved-mobile.png",
    fullPage: true,
  });
});

test("direct API enforces scope, exact inputs, DST boundaries, paging and concurrent single allocation", async () => {
  const own = await client("sophia"),
    hr = await client("amelia"),
    manager = await client("marcus"),
    foreign = await client("outsider");
  const anonymous = await request.newContext({ baseURL: origin });
  const contexts = [own, hr, manager, foreign, anonymous];
  try {
    for (const path of [
      "/api/v1/time/sheets",
      "/api/v1/time/attendance/E003",
      "/api/v1/time/history/E003",
    ])
      expect((await anonymous.get(path)).status()).toBe(401);
    const week = weekAgo(4),
      input = {
        week,
        zone: "Etc/UTC",
        version: 0,
        entries: entries(week, 1),
        reason: "Single-minute actual allocation",
        idempotencyKey: crypto.randomUUID(),
      };
    const saved = await own.post("/api/v1/time/sheets", {
      headers: await csrf(own),
      data: input,
    });
    expect(saved.status()).toBe(200);
    const sheet = (await checkResponse(
      saved,
      "/api/v1/time/sheets",
      "post",
    )) as Sheet;
    for (const version of [null, -1, 0.5, 1e40])
      expect(
        (
          await own.post("/api/v1/time/sheets", {
            headers: await csrf(own),
            data: { ...input, version, idempotencyKey: crypto.randomUUID() },
          })
        ).status(),
      ).toBe(400);
    expect(
      (await foreign.get(`/api/v1/time/sheets/${sheet.id}`)).status(),
    ).toBe(403);
    expect((await foreign.get("/api/v1/time/history/E003")).status()).toBe(403);
    expect((await foreign.get("/api/v1/time/attendance/E003")).status()).toBe(
      403,
    );
    expect(
      (await own.get("/api/v1/time/attendance/E003?size=51")).status(),
    ).toBe(400);
    const gap = await own.post("/api/v1/time/corrections", {
      headers: await csrf(own),
      data: {
        version: 0,
        start: "2026-03-29T01:30:00+00:00",
        end: "2026-03-29T03:00:00+01:00",
        zone: "Europe/London",
        reason: "Invalid daylight-saving gap",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(gap.status()).toBe(400);
    const submitted = await own.post(
      `/api/v1/time/sheets/${sheet.id}/actions`,
      {
        headers: await csrf(own),
        data: {
          version: 0,
          action: "SUBMIT",
          comment: "Please review",
          idempotencyKey: crypto.randomUUID(),
        },
      },
    );
    expect(submitted.status()).toBe(200);
    expect(
      (
        await own.post(`/api/v1/time/sheets/${sheet.id}/actions`, {
          headers: await csrf(own),
          data: {
            version: 1,
            action: "APPROVE",
            comment: "Forbidden self",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(403);
    const headers = await Promise.all([csrf(hr), csrf(manager)]);
    const results = await Promise.all(
      [hr, manager].map((c, i) =>
        c.post(`/api/v1/time/sheets/${sheet.id}/actions`, {
          headers: headers[i],
          data: {
            version: 1,
            action: "APPROVE",
            comment: "Independent concurrent review",
            idempotencyKey: crypto.randomUUID(),
          },
        }),
      ),
    );
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const context = (await (
      await own.get(`/api/v1/time/sheets/${sheet.id}`)
    ).json()) as SheetContext;
    expect(context.allocatedMinutes).toBe(1);
    expect(context.allocations).toHaveLength(1);
    const validate = schemaValidator("TimeSheetPage");
    expect(
      validate(
        (await (
          await manager.get("/api/v1/time/sheets?review=true")
        ).json()) as unknown,
      ),
    ).toBe(true);
    const history = await own.get("/api/v1/time/history/E003?size=1");
    const page = (await checkResponse(
      history,
      "/api/v1/time/history/{employeeId}",
    )) as TimePage<unknown>;
    expect(page.items).toHaveLength(1);
    expect(page.total).toBeGreaterThan(1);
  } finally {
    await Promise.all(contexts.map((c) => c.dispose()));
  }
});

test("attendance read recovery and keyboard discard preserve a draft until explicitly closed", async ({
  page,
}) => {
  await signIn(page, "sophia");
  await page.route(
    "**/api/v1/time/attendance/E003?*",
    async (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          status: 503,
          detail: "Synthetic temporary attendance outage",
        }),
      }),
    { times: 1 },
  );
  await timeNav(page);
  await expect(
    page.getByRole("alert", { name: "Time workflow errors", exact: true }),
  ).toBeFocused();
  await page
    .getByRole("button", { name: "Retry attendance", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Time workflow errors", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "My timesheets", exact: true })
    .click();
  await page
    .getByRole("button", { name: "New timesheet", exact: true })
    .click();
  const reason = page.getByLabel("Timesheet reason");
  await reason.fill("Retain this keyboard draft");
  await reason.focus();
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("button", { name: "Cancel", exact: true }),
  ).toBeFocused();
  page.once("dialog", async (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(reason).toHaveValue("Retain this keyboard draft");
  page.once("dialog", async (d) => d.accept());
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "New timesheet", exact: true }),
  ).toBeFocused();
});
