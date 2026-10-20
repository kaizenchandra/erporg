import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type {
  CalendarEntry,
  CalendarPage,
} from "@/features/settings/calendar-contracts";
const day = (offset = 0) => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + offset);
  return d.toISOString().slice(0, 10);
};
async function signIn(page: Page, user = "amelia") {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
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
const command = (code: string) => ({
  calendar: {
    code,
    name: "Synthetic company working policy 李",
    timeZone: "Europe/London",
    effectiveFrom: day(),
    effectiveUntil: day(30),
    workingDays: [1, 2, 3, 4, 5],
    closedDates: [day(1)],
  },
  reason: "Explicit company closure and weekday policy",
  idempotencyKey: crypto.randomUUID(),
});
test("HR records a calendar with preserved validation, lost-response replay, private provenance and mobile authoritative date preview", async ({
  page,
}) => {
  await signIn(page);
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  const section = page.getByRole("region", {
    name: "Company calendars",
    exact: true,
  });
  await section.getByLabel("Calendar code or name").fill("CAL-UI");
  await section
    .getByRole("button", { name: "Search calendars", exact: true })
    .click();
  await expect(
    section.getByText("No company calendars match these filters.", {
      exact: false,
    }),
  ).toBeVisible();
  await section
    .getByRole("button", { name: "Create company calendar", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByLabel("Calendar code", { exact: false })
    .fill("CAL-UI");
  await page
    .getByLabel("Calendar name", { exact: false })
    .fill("Company working policy 李");
  await page
    .getByLabel("Calendar time zone", { exact: false })
    .fill("Europe/London");
  await page.getByLabel("Valid from", { exact: false }).fill(day());
  await page.getByLabel("Valid through", { exact: false }).fill(day(-1));
  await page.getByLabel("Company closure dates", { exact: false }).fill(day(1));
  await page
    .getByLabel("Reason for calendar", { exact: false })
    .fill("Company closure recorded from approved synthetic policy");
  await page
    .getByRole("button", { name: "Record company calendar", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Company calendar errors" }),
  ).toBeFocused();
  await expect(
    page.getByLabel("Valid through", { exact: false }),
  ).toHaveAttribute("aria-invalid", "true");
  await expect(
    page.getByLabel("Reason for calendar", { exact: false }),
  ).toHaveValue("Company closure recorded from approved synthetic policy");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  page.once("dialog", async (d) => {
    expect(d.message()).toContain("Discard unsaved company calendar");
    await d.dismiss();
  });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.getByLabel("Valid through", { exact: false }).fill(day(30));
  let original: CalendarEntry | undefined;
  await page.route(
    "**/api/v1/company-calendars",
    async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      original = (await response.json()) as CalendarEntry;
      await route.abort("failed");
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Record company calendar", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Company calendar errors" }),
  ).toContainText("Entries are preserved");
  await expect(
    page.getByRole("dialog").getByLabel("Calendar code", { exact: false }),
  ).toHaveValue("CAL-UI");
  await page
    .getByRole("button", { name: "Record company calendar", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    section.getByText("CAL-UI recorded.", { exact: false }),
  ).toBeVisible();
  const list = (await checkResponse(
    await page.request.get("/api/v1/company-calendars?search=CAL-UI"),
    "/api/v1/company-calendars",
  )) as CalendarPage;
  expect(list.total).toBe(1);
  expect(list.items[0]).toEqual(original);
  await section
    .getByRole("button", { name: "Inspect calendar · CAL-UI", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByText("Company working policy 李", { exact: true }),
  ).toBeVisible();
  await expect(dialog).toContainText(original!.correlationId);
  await page.getByLabel("Preview local date").fill(day(-1));
  await page
    .getByRole("button", { name: "Preview working date", exact: true })
    .click();
  await expect(dialog.getByRole("alert")).toBeFocused();
  await page.getByLabel("Preview local date").fill(day(1));
  await page
    .getByRole("button", { name: "Preview working date", exact: true })
    .click();
  await expect(dialog.getByRole("status")).toContainText("Company closure");
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
  await page.screenshot({ path: "docs/evidence/company-calendar-mobile.png" });
  await page.keyboard.press("Escape");
  await expect(
    section.getByRole("button", {
      name: "Inspect calendar · CAL-UI",
      exact: true,
    }),
  ).toBeFocused();
});
test("calendar APIs enforce HR/tenant scope, date policy, stable and conflicting replay, and concurrent unique creation", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    for (const a of [self, manager, outside]) {
      for (const path of [
        "/api/v1/company-calendars",
        "/api/v1/company-calendars/time-zones",
        "/api/v1/company-calendars/CAL-UI",
        "/api/v1/company-calendars/CAL-UI/preview?date=" + day(),
      ])
        expect(
          (
            await a.c.get(path, {
              headers: { "X-Role": "HR", "X-Tenant": "atlas" },
            })
          ).status(),
        ).toBe(403);
      expect(
        (
          await a.c.post("/api/v1/company-calendars", {
            headers: a.headers,
            data: command("CAL-DENY"),
          })
        ).status(),
      ).toBe(403);
    }
    const c = command("CAL-API");
    for (const patch of [
      { workingDays: [1.5] },
      { workingDays: [] },
      { workingDays: [1, 1] },
      { workingDays: [0] },
      { closedDates: [day(31)] },
      { closedDates: [day(1), day(1)] },
      { closedDates: ["2027-02-29"] },
      { effectiveFrom: day(-1) },
      { effectiveUntil: day(-1) },
      { timeZone: "Invented/Zone" },
    ])
      expect(
        (
          await hr.c.post("/api/v1/company-calendars", {
            headers: hr.headers,
            data: {
              ...c,
              idempotencyKey: crypto.randomUUID(),
              calendar: { ...c.calendar, ...patch },
            },
          })
        ).status(),
      ).toBe(400);
    const first = await hr.c.post("/api/v1/company-calendars", {
      headers: hr.headers,
      data: c,
    });
    expect(first.status()).toBe(200);
    const saved = (await checkResponse(
      first,
      "/api/v1/company-calendars",
      "post",
    )) as CalendarEntry;
    expect(
      await (
        await hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: c,
        })
      ).json(),
    ).toEqual(saved);
    expect(
      (
        await hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: { ...c, reason: "Conflicting policy" },
        })
      ).status(),
    ).toBe(409);
    const race = command("CAL-RACE");
    const receipts = await Promise.all(
      [1, 2].map(() =>
        hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: race,
        }),
      ),
    );
    expect(receipts.map((r) => r.status())).toEqual([200, 200]);
    expect(await receipts[0].json()).toEqual(await receipts[1].json());
    const one = command("CAL-UNIQUE");
    const unique = await Promise.all(
      [1, 2].map(() =>
        hr.c.post("/api/v1/company-calendars", {
          headers: hr.headers,
          data: { ...one, idempotencyKey: crypto.randomUUID() },
        }),
      ),
    );
    expect(unique.map((r) => r.status()).sort()).toEqual([200, 409]);
    const preview = await checkResponse(
      await hr.c.get(
        "/api/v1/company-calendars/CAL-API/preview?date=" + day(1),
      ),
      "/api/v1/company-calendars/{code}/preview",
    );
    expect(preview).toMatchObject({
      classification: "COMPANY_CLOSURE",
      working: false,
    });
    await checkResponse(
      await hr.c.get("/api/v1/company-calendars/CAL-API"),
      "/api/v1/company-calendars/{code}",
    );
    await checkResponse(
      await hr.c.get("/api/v1/company-calendars/time-zones"),
      "/api/v1/company-calendars/time-zones",
    );
    for (const q of ["size=51", "page=-1", "status=INVALID", "search=%0A"])
      expect((await hr.c.get("/api/v1/company-calendars?" + q)).status()).toBe(
        400,
      );
    const empty = (await (
      await hr.c.get("/api/v1/company-calendars?search=%25&tenant=forged")
    ).json()) as CalendarPage;
    expect(empty.total).toBe(0);
  } finally {
    for (const a of [hr, self, manager, outside]) await a.c.dispose();
  }
});
test("calendar list recovers focused reads, pages retained data and cancels stale filter responses", async ({
  page,
}) => {
  await signIn(page);
  for (let i = 0; i < 12; i++)
    expect(
      (
        await page.request.post("/api/v1/company-calendars", {
          headers: await (async () => {
            const t = await (await page.request.get("/api/csrf")).json();
            return { [t.headerName]: t.token };
          })(),
          data: command("CAL-PAGE-" + String(i).padStart(2, "0")),
        })
      ).status(),
    ).toBe(200);
  await page.route(
    "**/api/v1/company-calendars?**",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({ detail: "Synthetic temporary unavailable" }),
      }),
    { times: 1 },
  );
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  const section = page.getByRole("region", {
    name: "Company calendars",
    exact: true,
  });
  await expect(section.getByRole("alert")).toBeFocused();
  await section
    .getByRole("button", { name: "Retry company calendars", exact: true })
    .click();
  await expect(
    section.getByRole("button", {
      name: "Create company calendar",
      exact: true,
    }),
  ).toBeEnabled();
  await section.getByLabel("Calendar code or name").fill("CAL-PAGE-");
  await section
    .getByRole("button", { name: "Search calendars", exact: true })
    .click();
  await expect(
    section.getByText("12 calendars", { exact: false }),
  ).toBeVisible();
  await section
    .getByRole("button", { name: "Next calendars", exact: true })
    .click();
  await expect(
    section.getByRole("button", {
      name: "Inspect calendar · CAL-PAGE-10",
      exact: true,
    }),
  ).toBeVisible();
  let release: () => void = () => {};
  const gate = new Promise<void>((r) => {
    release = r;
  });
  let announce: () => void = () => {};
  const seen = new Promise<void>((r) => {
    announce = r;
  });
  await page.route("**/api/v1/company-calendars?**", async (route) => {
    if (
      new URL(route.request().url()).searchParams.get("status") === "PLANNED"
    ) {
      announce();
      await gate;
      try {
        await route.continue();
      } catch {}
    } else await route.continue();
  });
  await section.getByLabel("Calendar availability").selectOption("PLANNED");
  await seen;
  await section.getByLabel("Calendar availability").selectOption("EXPIRED");
  await expect(
    section.getByText("No company calendars match these filters.", {
      exact: false,
    }),
  ).toBeVisible();
  release();
  await expect(section.getByLabel("Calendar availability")).toHaveValue(
    "EXPIRED",
  );
  await expect(
    section.getByText("No company calendars match these filters.", {
      exact: false,
    }),
  ).toBeVisible();
});
