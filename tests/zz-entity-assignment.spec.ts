import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { Employee, ChangeRequest } from "@/lib/contracts";
import type { EntityAssignmentReview } from "@/features/people/entity-assignment-context";
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
async function signIn(page: Page, user: string) {
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
async function signOut(page: Page) {
  if (await page.getByRole("dialog").count())
    await page
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
}
async function profile(page: Page) {
  const e = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as Employee;
  await page.getByLabel("Search employees").fill("E003");
  await page
    .getByRole("button", { name: `View ${e.name}`, exact: true })
    .click();
  return e;
}
const reference = (code: string) => ({
  entity: {
    code,
    name: "Declared synthetic company 李 ".repeat(3).trim(),
    countryCode: "GB",
    currencyCode: "GBP",
    timeZone: "Europe/London",
    effectiveFrom: day(),
  },
  reason: "Supplied reference; not verified registration",
  idempotencyKey: crypto.randomUUID(),
});
test("HR declared entity proposal has independent impact review, private profile and explicit unassignment", async ({
  page,
}) => {
  await signIn(page, "amelia");
  const token = await (await page.request.get("/api/csrf")).json();
  const headers = { [token.headerName]: token.token };
  expect(
    (
      await page.request.post("/api/v1/legal-entities", {
        headers,
        data: reference("AY-UI"),
      })
    ).status(),
  ).toBe(200);
  const before = await profile(page);
  expect(before.legalEntityCode).toBe("");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.route(
    "**/api/v1/legal-entities?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({ status: 503, title: "Choices unavailable" }),
      }),
    { times: 1 },
  );
  await page
    .getByLabel("Information to change")
    .selectOption("legalEntityCode");
  await expect(page.locator(".entity-picker").getByRole("alert")).toBeVisible();
  await page
    .getByRole("button", { name: "Retry entity choices", exact: true })
    .click();
  await page.getByLabel("Find a declared entity").fill("AY-UI");
  await expect(
    page.getByLabel("New value").locator('option[value="AY-UI"]'),
  ).toHaveCount(1);
  await page.getByLabel("New value").selectOption("AY-UI");
  await page
    .getByLabel("Reason for change")
    .fill("Declare company reference without activating country rules");
  await page.getByLabel("Effective date", { exact: false }).fill(day(-1));
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("New value")).toHaveValue("AY-UI");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByLabel("New value")).toHaveValue("AY-UI");
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
  const requests = (await (
    await page.request.get("/api/v1/requests")
  ).json()) as ChangeRequest[];
  const own = requests.filter(
    (r) => r.field === "legalEntityCode" && r.newValue === "AY-UI",
  );
  expect(own).toHaveLength(1);
  await signOut(page);
  await signIn(page, "olivia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page.route(
    `**/api/v1/requests/${own[0].id}/entity-context`,
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          status: 503,
          title: "Review context unavailable",
        }),
      }),
    { times: 1 },
  );
  await page
    .locator(".request-card")
    .filter({ hasText: "AY-UI" })
    .getByRole("button", { name: "View request" })
    .click();
  const impact = page.getByRole("region", {
    name: "Declared entity assignment impact",
    exact: true,
  });
  await expect(impact.getByRole("alert")).toBeFocused();
  await expect(
    page.getByRole("button", { name: "Confirm approval", exact: true }),
  ).toBeDisabled();
  await impact
    .getByRole("button", { name: "Retry entity context", exact: true })
    .click();
  await expect(impact).toContainText("GB · GBP · Europe/London");
  await expect(impact).toContainText("unverified");
  const context = (await checkResponse(
    await page.request.get(`/api/v1/requests/${own[0].id}/entity-context`),
    "/api/v1/requests/{id}/entity-context",
  )) as EntityAssignmentReview;
  expect(context.retirementBlocked).toBe(false);
  await page.setViewportSize({ width: 390, height: 844 });
  await impact.scrollIntoViewIfNeeded();
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
    path: "docs/evidence/entity-assignment-review-mobile.png",
  });
  await page
    .getByLabel("Decision comment")
    .fill("Independent declared-reference and episode review");
  await page
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "sophia");
  await page
    .getByRole("button", { name: "Open your profile", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("AY-UI");
  const self = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as Employee;
  expect(self.legalEntityCode).toBe("AY-UI");
  expect(self.email).toBe(before.email);
  await page.screenshot({
    path: "docs/evidence/entity-assignment-profile-mobile.png",
  });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await expect(
    page
      .getByLabel("Information to change")
      .locator('option[value="legalEntityCode"]'),
  ).toHaveCount(0);
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await signIn(page, "amelia");
  await profile(page);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page
    .getByLabel("Information to change")
    .selectOption("legalEntityCode");
  await page
    .getByRole("checkbox", {
      name: "Unassign the current declared entity",
      exact: true,
    })
    .check();
  await expect(page.getByLabel("New value")).toBeDisabled();
  await page
    .getByLabel("Reason for change")
    .fill("Explicit declared reference unassignment");
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  const pending = (
    (await (
      await page.request.get("/api/v1/requests")
    ).json()) as ChangeRequest[]
  ).find(
    (r) =>
      r.field === "legalEntityCode" &&
      r.newValue === "" &&
      r.status === "SUBMITTED",
  );
  expect(pending).toBeTruthy();
  const reviewer = await identity("olivia");
  try {
    expect(
      (
        await reviewer.c.post(`/api/v1/requests/${pending!.id}/decision`, {
          headers: reviewer.headers,
          data: {
            decision: "APPROVED",
            comment: "Independent explicit entity unassignment",
          },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await reviewer.c.dispose();
  }
  expect(
    (await (await page.request.get("/api/v1/employees/E003")).json())
      .legalEntityCode,
  ).toBe("");
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await page.getByLabel("Entity code or name").fill("AY-UI");
  await page
    .getByRole("button", { name: "Search references", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Manage retirement · AY-UI", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "Approved assignment history freezes this declared reference",
  );
  await expect(
    page.getByRole("button", {
      name: "Submit retirement for review",
      exact: true,
    }),
  ).toHaveCount(0);
});
test("entity APIs enforce privacy, repeatability and one-winner retirement arbitration", async () => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outsider = await identity("outsider");
  try {
    for (const code of ["AY-API", "AY-RACE", "AY-PENDING"])
      expect(
        (
          await hr.c.post("/api/v1/legal-entities", {
            headers: hr.headers,
            data: reference(code),
          })
        ).status(),
      ).toBe(200);
    const command = async (code: string, date = day()) => ({
      employeeId: "E003",
      field: "legalEntityCode",
      newValue: code,
      effectiveDate: date,
      reason: "Declared reference only",
      version: (await (await hr.c.get("/api/v1/employees/E003")).json())
        .version,
      idempotencyKey: crypto.randomUUID(),
    });
    const approve = (id: string) =>
      reviewer.c.post(`/api/v1/requests/${id}/decision`, {
        headers: reviewer.headers,
        data: { decision: "APPROVED", comment: "Independent reference review" },
      });
    const reject = (id: string) =>
      reviewer.c.post(`/api/v1/requests/${id}/decision`, {
        headers: reviewer.headers,
        data: {
          decision: "REJECTED",
          comment: "Reference unavailable; resubmit",
        },
      });
    const data = await command("AY-API");
    for (const a of [self, manager, outsider])
      expect(
        (
          await a.c.post("/api/v1/requests", {
            headers: { ...a.headers, "X-Role": "HR", "X-Tenant": "atlas" },
            data,
          })
        ).status(),
      ).toBe(403);
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
            data: { decision: "APPROVED", comment: "Forbidden reviewer" },
          })
        ).status(),
      ).toBe(403);
    for (const a of [self, manager, outsider])
      expect(
        (await a.c.get(`/api/v1/requests/${r.id}/entity-context`)).status(),
      ).toBe(403);
    expect(
      (await Promise.all([approve(r.id), approve(r.id)]))
        .map((r) => r.status())
        .sort(),
    ).toEqual([200, 409]);
    expect(
      (await (await self.c.get("/api/v1/employees/E003")).json())
        .legalEntityCode,
    ).toBe("AY-API");
    const managerProfile = (await checkResponse(
      await manager.c.get("/api/v1/employees/E003"),
      "/api/v1/employees/{id}",
    )) as Employee;
    expect(managerProfile.legalEntityCode).toBeNull();
    for (const path of ["/api/v1/employees/E003/history", "/api/v1/requests"]) {
      const rows = (await (await manager.c.get(path)).json()) as {
        field: string;
      }[];
      expect(rows.some((x) => x.field === "legalEntityCode")).toBe(false);
    }
    const directory = await (
      await manager.c.get("/api/v1/directory?search=E003")
    ).json();
    expect(directory.items[0].legalEntityCode).toBeNull();
    expect((await outsider.c.get("/api/v1/employees/E003")).status()).toBe(403);
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
          data: { ...data, newValue: "AY-RACE" },
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
    expect(
      (await (await self.c.get("/api/v1/employees/E003")).json())
        .legalEntityCode,
    ).toBe("AY-API");
    const pending = await (
      await hr.c.post("/api/v1/requests", {
        headers: hr.headers,
        data: await command("AY-RACE", day(2)),
      })
    ).json();
    const retirement = {
      version: 0,
      effectiveDate: day(4),
      reason: "Concurrent declared-reference retirement",
      idempotencyKey: crypto.randomUUID(),
    };
    const race = await Promise.all([
      approve(pending.id),
      hr.c.post("/api/v1/legal-entities/AY-RACE/retirement/requests", {
        headers: hr.headers,
        data: retirement,
      }),
    ]);
    expect(race.map((r) => r.status()).sort()).toEqual([200, 409]);
    if (race[0].status() === 409) {
      expect((await reject(pending.id)).status()).toBe(200);
      const retirementRequest = await race[1].json();
      expect(
        (
          await reviewer.c.post(
            `/api/v1/legal-entities/AY-RACE/retirement/requests/${retirementRequest.id}/decision`,
            {
              headers: reviewer.headers,
              data: {
                decision: "APPROVED",
                comment: "Independent unavailable reference",
                version: 0,
                idempotencyKey: crypto.randomUUID(),
              },
            },
          )
        ).status(),
      ).toBe(200);
    }
    expect(
      (
        await hr.c.post("/api/v1/legal-entities/AY-API/retirement/requests", {
          headers: hr.headers,
          data: retirement,
        })
      ).status(),
    ).toBe(409);
    const second = await (
      await hr.c.post("/api/v1/requests", {
        headers: hr.headers,
        data: await command("AY-PENDING", day(3)),
      })
    ).json();
    const retirementResponse = await hr.c.post(
      "/api/v1/legal-entities/AY-PENDING/retirement/requests",
      {
        headers: hr.headers,
        data: { ...retirement, idempotencyKey: crypto.randomUUID() },
      },
    );
    expect(retirementResponse.status()).toBe(200);
    expect(
      (
        await (
          await reviewer.c.get(`/api/v1/requests/${second.id}/entity-context`)
        ).json()
      ).retirementBlocked,
    ).toBe(true);
    expect((await approve(second.id)).status()).toBe(409);
    expect((await reject(second.id)).status()).toBe(200);
    const pendingRetirement = await retirementResponse.json();
    expect(
      (
        await reviewer.c.post(
          `/api/v1/legal-entities/AY-PENDING/retirement/requests/${pendingRetirement.id}/decision`,
          {
            headers: reviewer.headers,
            data: {
              decision: "APPROVED",
              comment: "Independent reference retirement",
              version: 0,
              idempotencyKey: crypto.randomUUID(),
            },
          },
        )
      ).status(),
    ).toBe(200);
  } finally {
    for (const a of [hr, reviewer, self, manager, outsider])
      await a.c.dispose();
  }
});
