import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type {
  CalendarAmendmentContext,
  CalendarRevisionEntry,
} from "@/features/settings/calendar-amendment-contracts";
const day = (n = 0) => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};
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
  const token = await (await c.get("/api/csrf")).json();
  return { c, headers: { [token.headerName]: token.token } };
}
const base = (code: string, from = day()) => ({
  calendar: {
    code,
    name: "Synthetic dated company policy 李",
    timeZone: "Europe/London",
    effectiveFrom: from,
    effectiveUntil: day(30),
    workingDays: [1, 2, 3, 4, 5, 6, 7],
    closedDates: [],
  },
  reason: "Explicit company configuration",
  idempotencyKey: crypto.randomUUID(),
});
const command = (date = day(2)) => ({
  version: 0,
  effectiveDate: date,
  workingDays: [1],
  closedDates: [day(3)],
  reason: "Dated replacement for synthetic company policy",
  idempotencyKey: crypto.randomUUID(),
});
async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
test("HR amends future company policy with impact review, preserved errors, uncertain-response replay and historical/mobile context", async ({
  page,
}) => {
  await signIn(page);
  const t = await (await page.request.get("/api/csrf")).json();
  expect(
    (
      await page.request.post("/api/v1/company-calendars", {
        headers: { [t.headerName]: t.token },
        data: base("AMEND-UI"),
      })
    ).status(),
  ).toBe(200);
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  const section = page.getByRole("region", {
    name: "Company calendars",
    exact: true,
  });
  await section.getByLabel("Calendar code or name").fill("AMEND-UI");
  await section
    .getByRole("button", { name: "Search calendars", exact: true })
    .click();
  await section
    .getByRole("button", { name: "Inspect calendar · AMEND-UI", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Amend working policy", exact: true })
    .click();
  await expect(
    page.getByLabel("Amendment effective date", { exact: false }),
  ).toBeVisible();
  await page
    .getByLabel("Amendment effective date", { exact: false })
    .fill(day(-1));
  await page
    .getByLabel("Replacement closure dates", { exact: false })
    .fill(day(3));
  await page
    .getByLabel("Reason for amendment", { exact: false })
    .fill("Future company policy replacement; preserve earlier dates");
  for (const name of [
    "Tuesday",
    "Wednesday",
    "Thursday",
    "Friday",
    "Saturday",
    "Sunday",
  ])
    await page.getByRole("checkbox", { name, exact: true }).uncheck();
  await page
    .getByRole("button", { name: "Review amendment", exact: true })
    .click();
  await expect(
    page.getByRole("heading", {
      name: "Review calendar amendment",
      exact: true,
    }),
  ).toBeFocused();
  await page
    .getByRole("button", { name: "Confirm calendar amendment", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Calendar amendment errors" }),
  ).toBeFocused();
  await expect(
    page.getByLabel("Amendment effective date", { exact: false }),
  ).toHaveAttribute("aria-invalid", "true");
  await expect(
    page.getByLabel("Reason for amendment", { exact: false }),
  ).toHaveValue("Future company policy replacement; preserve earlier dates");
  page.once("dialog", async (d) => {
    expect(d.message()).toContain("Discard unsaved calendar amendment");
    await d.dismiss();
  });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await expect(
    page.getByLabel("Reason for amendment", { exact: false }),
  ).toBeVisible();
  await page
    .getByLabel("Amendment effective date", { exact: false })
    .fill(day(2));
  await page
    .getByRole("button", { name: "Review amendment", exact: true })
    .click();
  let recorded: CalendarRevisionEntry | undefined;
  await page.route(
    "**/api/v1/company-calendars/AMEND-UI/amendments",
    async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      recorded = (await response.json()) as CalendarRevisionEntry;
      await route.abort("failed");
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Confirm calendar amendment", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Calendar amendment errors" }),
  ).toContainText("Entries are preserved");
  await page
    .getByRole("button", { name: "Review amendment", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm calendar amendment", exact: true })
    .click();
  await expect(
    page.getByText("Revision 1 recorded for AMEND-UI.", { exact: true }),
  ).toBeVisible();
  const ctx = (await checkResponse(
    await page.request.get("/api/v1/company-calendars/AMEND-UI/amendments"),
    "/api/v1/company-calendars/{code}/amendments",
  )) as CalendarAmendmentContext;
  expect(ctx.version).toBe(1);
  expect(ctx.currentVersion).toBe(0);
  expect(ctx.history).toEqual([recorded]);
  await page.getByLabel("Preview local date").fill(day(1));
  await page
    .getByRole("button", { name: "Preview working date", exact: true })
    .click();
  await expect(
    page.getByRole("dialog").getByText(/Working day.*revision 0/),
  ).toBeVisible();
  await page.getByLabel("Preview local date").fill(day(3));
  await page
    .getByRole("button", { name: "Preview working date", exact: true })
    .click();
  await expect(
    page.getByRole("dialog").getByText(/Company closure.*revision 1/),
  ).toBeVisible();
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
  await page.screenshot({
    path: "docs/evidence/calendar-amendment-mobile.png",
  });
  await page.keyboard.press("Escape");
  await expect(
    section.getByRole("button", {
      name: "Inspect calendar · AMEND-UI",
      exact: true,
    }),
  ).toBeFocused();
});
test("amendment APIs enforce role/tenant policy, exact versions, conflicting replay and a concurrent winner without early policy application", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    expect(
      (
        await hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: base("AMEND-API"),
        })
      ).status(),
    ).toBe(200);
    const path = "/api/v1/company-calendars/AMEND-API/amendments";
    for (const a of [self, manager, outside]) {
      expect(
        (
          await a.c.get(path, {
            headers: { "X-Role": "HR", "X-Tenant": "atlas" },
          })
        ).status(),
      ).toBe(403);
      expect(
        (
          await a.c.post(path, { headers: a.headers, data: command() })
        ).status(),
      ).toBe(403);
    }
    const c = command();
    for (const patch of [
      { version: 0.5 },
      { version: null },
      { version: -1 },
      { effectiveDate: day(-1) },
      { effectiveDate: "2027-02-29" },
      { closedDates: [day(1)] },
      { workingDays: [1.5] },
    ])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...c, ...patch, idempotencyKey: crypto.randomUUID() },
          })
        ).status(),
      ).toBe(400);
    const competing = [command(day(2)), command(day(3))];
    const race = await Promise.all(
      competing.map((data) => hr.c.post(path, { headers: hr.headers, data })),
    );
    expect(race.map((r) => r.status()).sort()).toEqual([200, 409]);
    const index = race[0].status() === 200 ? 0 : 1;
    const saved = (await checkResponse(
      race[index],
      "/api/v1/company-calendars/{code}/amendments",
      "post",
    )) as CalendarRevisionEntry;
    expect(
      await (
        await hr.c.post(path, { headers: hr.headers, data: competing[index] })
      ).json(),
    ).toEqual(saved);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...competing[index], reason: "Different replay reason" },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...command(day(4)), closedDates: [day(4)] },
        })
      ).status(),
    ).toBe(409);
    const ctx = (await checkResponse(
      await hr.c.get(path + "?tenant=forged"),
      "/api/v1/company-calendars/{code}/amendments",
    )) as CalendarAmendmentContext;
    expect(ctx.history).toHaveLength(1);
    expect(ctx.currentVersion).toBe(0);
    expect(ctx.base.calendar.workingDays).toHaveLength(7);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...command(saved.revision.effectiveDate), version: 1 },
        })
      ).status(),
    ).toBe(400);
    for (const [code, start, effective] of [
      ["AMEND-NOW", day(), day()],
      ["AMEND-FUTURE", day(5), day(5)],
    ]) {
      expect(
        (
          await hr.c.post("/api/v1/company-calendars", {
            headers: hr.headers,
            data: base(code, start),
          })
        ).status(),
      ).toBe(200);
      const write = { ...command(effective), closedDates: [effective] };
      const replies = await Promise.all(
        [1, 2].map(() =>
          hr.c.post(`/api/v1/company-calendars/${code}/amendments`, {
            headers: hr.headers,
            data: write,
          }),
        ),
      );
      expect(replies.map((r) => r.status())).toEqual([200, 200]);
      expect(await replies[0].json()).toEqual(await replies[1].json());
      const context = (await (
        await hr.c.get(`/api/v1/company-calendars/${code}/amendments`)
      ).json()) as CalendarAmendmentContext;
      expect(context.currentVersion).toBe(code === "AMEND-NOW" ? 1 : 0);
      const preview = await checkResponse(
        await hr.c.get(
          `/api/v1/company-calendars/${code}/preview?date=${effective}`,
        ),
        "/api/v1/company-calendars/{code}/preview",
      );
      expect(preview).toMatchObject({
        revision: 1,
        classification: "COMPANY_CLOSURE",
        working: false,
      });
    }
  } finally {
    for (const a of [hr, self, manager, outside]) await a.c.dispose();
  }
});
