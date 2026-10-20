import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type {
  CalendarAmendmentContext,
  CalendarRevisionEntry,
} from "@/features/settings/calendar-amendment-contracts";
import type { CalendarRetirementEntry } from "@/features/settings/calendar-retirement-contracts";
import type { CalendarPage } from "@/features/settings/calendar-contracts";
const day = (n = 0) => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + n);
  return d.toISOString().slice(0, 10);
};
const base = (code: string) => ({
  calendar: {
    code,
    name: "Synthetic retirement calendar 李",
    timeZone: "Europe/London",
    effectiveFrom: day(),
    effectiveUntil: day(30),
    workingDays: [1, 2, 3, 4, 5, 6, 7],
    closedDates: [],
  },
  reason: "Company calendar reference",
  idempotencyKey: crypto.randomUUID(),
});
const amendment = () => ({
  version: 0,
  effectiveDate: day(1),
  workingDays: [1, 2, 3, 4, 5, 6, 7],
  closedDates: [day(2)],
  reason: "Retained dated closure",
  idempotencyKey: crypto.randomUUID(),
});
const retirement = (version = 1, date = day(3)) => ({
  version,
  effectiveDate: date,
  reason: "Retire unassigned company calendar while preserving history",
  idempotencyKey: crypto.randomUUID(),
});
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
async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
test("HR retires dated calendar with read recovery, impact confirmation, uncertain-response replay and preserved earlier mobile preview", async ({
  page,
}) => {
  await signIn(page);
  const token = await (await page.request.get("/api/csrf")).json();
  const headers = { [token.headerName]: token.token };
  expect(
    (
      await page.request.post("/api/v1/company-calendars", {
        headers,
        data: base("RET-UI"),
      })
    ).status(),
  ).toBe(200);
  expect(
    (
      await page.request.post("/api/v1/company-calendars/RET-UI/amendments", {
        headers,
        data: amendment(),
      })
    ).status(),
  ).toBe(200);
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  const section = page.getByRole("region", {
    name: "Company calendars",
    exact: true,
  });
  await section.getByLabel("Calendar code or name").fill("RET-UI");
  await section
    .getByRole("button", { name: "Search calendars", exact: true })
    .click();
  await section
    .getByRole("button", { name: "Inspect calendar · RET-UI", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Retire calendar", exact: true }),
  ).toBeVisible();
  let release: () => void = () => {};
  const gate = new Promise<void>((r) => {
    release = r;
  });
  let announce: () => void = () => {};
  const seen = new Promise<void>((r) => {
    announce = r;
  });
  await page.route(
    "**/api/v1/company-calendars/RET-UI/amendments",
    async (route) => {
      announce();
      await gate;
      try {
        await route.continue();
      } catch {}
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Retire calendar", exact: true })
    .click();
  await seen;
  await expect(
    page.getByText("Loading retirement context…", { exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  release();
  await expect(
    page.getByRole("button", { name: "Retire calendar", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("form", { name: "Calendar retirement form" }),
  ).toHaveCount(0);
  await page.route(
    "**/api/v1/company-calendars/RET-UI/amendments",
    (r) =>
      r.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ detail: "Synthetic unavailable context" }),
      }),
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Retire calendar", exact: true })
    .click();
  await expect(page.getByRole("dialog").getByRole("alert")).toBeFocused();
  await page
    .getByRole("button", { name: "Retry retirement context", exact: true })
    .click();
  await page
    .getByLabel("Retirement effective date", { exact: false })
    .fill(day(1));
  await page
    .getByLabel("Reason for retirement", { exact: false })
    .fill("Retire company policy without changing earlier working dates");
  await page
    .getByRole("button", { name: "Review retirement", exact: true })
    .click();
  await expect(
    page.getByRole("heading", {
      name: "Review calendar retirement",
      exact: true,
    }),
  ).toBeFocused();
  await page
    .getByRole("button", { name: "Confirm calendar retirement", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Calendar retirement errors" }),
  ).toBeFocused();
  await expect(
    page.getByLabel("Retirement effective date", { exact: false }),
  ).toHaveAttribute("aria-invalid", "true");
  await expect(
    page.getByLabel("Reason for retirement", { exact: false }),
  ).toHaveValue("Retire company policy without changing earlier working dates");
  page.once("dialog", async (d) => {
    expect(d.message()).toContain("Discard unsaved calendar retirement");
    await d.dismiss();
  });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page
    .getByLabel("Retirement effective date", { exact: false })
    .fill(day(3));
  await page
    .getByRole("button", { name: "Review retirement", exact: true })
    .click();
  let recorded: CalendarRetirementEntry | undefined;
  await page.route(
    "**/api/v1/company-calendars/RET-UI/retirement",
    async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      recorded = (await response.json()) as CalendarRetirementEntry;
      await route.abort("failed");
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Confirm calendar retirement", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Calendar retirement errors" }),
  ).toContainText("Entries are preserved");
  await page
    .getByRole("button", { name: "Review retirement", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm calendar retirement", exact: true })
    .click();
  await expect(
    page.getByText("Retirement recorded for RET-UI.", { exact: true }),
  ).toBeVisible();
  const context = (await checkResponse(
    await page.request.get("/api/v1/company-calendars/RET-UI/amendments"),
    "/api/v1/company-calendars/{code}/amendments",
  )) as CalendarAmendmentContext;
  expect(context.retirement).toEqual(recorded);
  expect(context.retirementStatus).toBe("SCHEDULED");
  expect(context.draft).toBeNull();
  expect(context.retirementDraft).toBeNull();
  await expect(
    page.getByRole("button", { name: "Amend working policy", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("button", { name: "Retire calendar", exact: true }),
  ).toHaveCount(0);
  await page.getByLabel("Preview local date").fill(day(2));
  await page
    .getByRole("button", { name: "Preview working date", exact: true })
    .click();
  await expect(
    page.getByRole("dialog").getByText(/Company closure.*revision 1/),
  ).toBeVisible();
  await page.getByLabel("Preview local date").fill(day(3));
  await page
    .getByRole("button", { name: "Preview working date", exact: true })
    .click();
  await expect(page.getByRole("dialog").getByRole("alert")).toBeFocused();
  await expect(page.getByRole("dialog").getByRole("alert")).toContainText(
    "retired for this date",
  );
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
    path: "docs/evidence/calendar-retirement-mobile.png",
  });
  await page.keyboard.press("Escape");
  await expect(
    section.getByRole("button", {
      name: "Inspect calendar · RET-UI",
      exact: true,
    }),
  ).toBeFocused();
  await expect(
    section.getByText("Retirement date:", { exact: false }),
  ).toBeVisible();
});
test("retirement APIs enforce exact version/tenant scope, stable replay, amendment interlock and immediate versus scheduled availability", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    expect(
      (
        await hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: base("RET-API"),
        })
      ).status(),
    ).toBe(200);
    const prior = amendment();
    const amended = await hr.c.post(
      "/api/v1/company-calendars/RET-API/amendments",
      { headers: hr.headers, data: prior },
    );
    expect(amended.status()).toBe(200);
    const original = (await amended.json()) as CalendarRevisionEntry;
    const path = "/api/v1/company-calendars/RET-API/retirement";
    for (const a of [self, manager, outside])
      expect(
        (
          await a.c.post(path, {
            headers: { ...a.headers, "X-Role": "HR", "X-Tenant": "atlas" },
            data: retirement(),
          })
        ).status(),
      ).toBe(403);
    for (const patch of [
      { version: null },
      { version: 0.5 },
      { version: -1 },
      { effectiveDate: day(-1) },
      { effectiveDate: day(1) },
      { effectiveDate: day(31) },
      { effectiveDate: "2027-02-29" },
      { reason: "" },
    ])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...retirement(), ...patch },
          })
        ).status(),
      ).toBe(400);
    expect(
      (
        await hr.c.post(path, { headers: hr.headers, data: retirement(0) })
      ).status(),
    ).toBe(409);
    const same = retirement();
    const replies = await Promise.all(
      [1, 2].map(() => hr.c.post(path, { headers: hr.headers, data: same })),
    );
    expect(replies.map((r) => r.status())).toEqual([200, 200]);
    const saved = (await checkResponse(
      replies[0],
      "/api/v1/company-calendars/{code}/retirement",
      "post",
    )) as CalendarRetirementEntry;
    expect(await replies[1].json()).toEqual(saved);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...same, reason: "Changed replay" },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await hr.c.post(path, { headers: hr.headers, data: retirement() })
      ).status(),
    ).toBe(409);
    expect(
      await (
        await hr.c.post("/api/v1/company-calendars/RET-API/amendments", {
          headers: hr.headers,
          data: prior,
        })
      ).json(),
    ).toEqual(original);
    expect(
      (
        await hr.c.post("/api/v1/company-calendars/RET-API/amendments", {
          headers: hr.headers,
          data: {
            ...amendment(),
            version: 1,
            effectiveDate: day(4),
            closedDates: [],
          },
        })
      ).status(),
    ).toBe(409);
    const before = await checkResponse(
      await hr.c.get(
        "/api/v1/company-calendars/RET-API/preview?date=" + day(2),
      ),
      "/api/v1/company-calendars/{code}/preview",
    );
    expect(before).toMatchObject({
      revision: 1,
      classification: "COMPANY_CLOSURE",
    });
    expect(
      (
        await hr.c.get(
          "/api/v1/company-calendars/RET-API/preview?date=" + day(3),
        )
      ).status(),
    ).toBe(409);
    for (const [code, date] of [
      ["RET-NOW", day()],
      ["RET-PLANNED", day(4)],
    ]) {
      expect(
        (
          await hr.c.post("/api/v1/company-calendars", {
            headers: hr.headers,
            data: base(code),
          })
        ).status(),
      ).toBe(200);
      expect(
        (
          await hr.c.post(`/api/v1/company-calendars/${code}/retirement`, {
            headers: hr.headers,
            data: retirement(0, date),
          })
        ).status(),
      ).toBe(200);
      const current = (await checkResponse(
        await hr.c.get(
          `/api/v1/company-calendars?search=${code}&status=CURRENT&tenant=forged`,
        ),
        "/api/v1/company-calendars",
      )) as CalendarPage;
      expect(current.total).toBe(code === "RET-NOW" ? 0 : 1);
      const retired = (await checkResponse(
        await hr.c.get(
          `/api/v1/company-calendars?search=${code}&status=RETIRED`,
        ),
        "/api/v1/company-calendars",
      )) as CalendarPage;
      expect(retired.total).toBe(code === "RET-NOW" ? 1 : 0);
      const all = (await (
        await hr.c.get(`/api/v1/company-calendars?search=${code}&status=ALL`)
      ).json()) as CalendarPage;
      expect(all.retirementDates[code]).toBe(date);
    }
    expect(
      (
        await hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: base("RET-RACE"),
        })
      ).status(),
    ).toBe(200);
    const race = await Promise.all([
      hr.c.post("/api/v1/company-calendars/RET-RACE/retirement", {
        headers: hr.headers,
        data: retirement(0),
      }),
      hr.c.post("/api/v1/company-calendars/RET-RACE/amendments", {
        headers: hr.headers,
        data: amendment(),
      }),
    ]);
    expect(race.map((r) => r.status()).sort()).toEqual([200, 409]);
    const outcome = (await (
      await hr.c.get("/api/v1/company-calendars/RET-RACE/amendments")
    ).json()) as CalendarAmendmentContext;
    expect((outcome.retirement ? 1 : 0) + outcome.history.length).toBe(1);
  } finally {
    for (const a of [hr, self, manager, outside]) await a.c.dispose();
  }
});
