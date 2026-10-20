import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
}
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
  const fresh = await (await c.get("/api/csrf")).json();
  return { c, headers: { [fresh.headerName]: fresh.token } };
}
test("HR creates a position with recoverable validation; employee browses scoped catalogue", async ({
  page,
}) => {
  await signIn(page, "amelia");
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const departments = await (
    await page.request.get("/api/v1/organisation/departments")
  ).json();
  const department = departments.find(
    (d: { effectiveFrom: string }) =>
      d.effectiveFrom <= new Date().toISOString().slice(0, 10),
  );
  const code = `ROLE-${Date.now()}`;
  const title = `Synthetic Principal Analyst ${Date.now()}`;
  await page
    .getByRole("button", { name: "Create position", exact: true })
    .click();
  await page.getByLabel("Position code", { exact: false }).fill("lowercase");
  await page.getByRole("dialog").getByLabel("Position title").fill(title);
  await page
    .getByRole("dialog")
    .getByLabel("Find a department", { exact: true })
    .fill(department.code);
  await page
    .getByLabel("Department for position")
    .selectOption(department.code);
  await page
    .getByLabel("Position configuration reason")
    .fill("Synthetic position approved by HR configuration owner");
  await page.getByRole("button", { name: "Save position" }).click();
  await expect(
    page.getByRole("alert", { name: "Position errors" }),
  ).toBeFocused();
  await expect(
    page.getByRole("dialog").getByLabel("Position title"),
  ).toHaveValue(title);
  await page.getByRole("alert").getByRole("link").click();
  await expect(
    page.getByLabel("Position code", { exact: false }),
  ).toBeFocused();
  await page.getByLabel("Position code", { exact: false }).fill(code);
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Save position" }).click();
  await expect(
    page.getByText("Position created and audit recorded."),
  ).toBeVisible();
  await page.getByLabel("Search positions").fill(code);
  await expect(page.locator(".position-card")).toHaveCount(1);
  await expect(page.locator(".position-card")).toContainText(title);
  await page.getByText("Position configuration history").click();
  await expect(
    page.locator(".history-event").filter({ hasText: code }),
  ).toContainText("Synthetic position approved");
  await page.screenshot({
    path: "docs/evidence/position-catalogue.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await signIn(page, "sophia");
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Create position", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText("Position configuration history")).toHaveCount(0);
  await page.getByLabel("Search positions").fill(code);
  await expect(page.locator(".position-card")).toContainText(title);
  await page.getByLabel("Search positions").fill("No-such-position-");
  await expect(
    page.getByText(/No positions match these filters/),
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
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
test("position API validates dates and roles, isolates tenants, replays and bounds server pages", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    other = await identity("outsider");
  try {
    const departments = await (
      await hr.c.get("/api/v1/organisation/departments")
    ).json();
    const date = new Date().toISOString().slice(0, 10);
    const department = departments.find(
      (d: { effectiveFrom: string }) => d.effectiveFrom <= date,
    );
    const code = `API-${Date.now()}`;
    const input = {
      code,
      title: `API role ${Date.now()}`,
      departmentCode: department.code,
      effectiveFrom: date,
      reason: "API position fixture",
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await self.c.post("/api/v1/organisation/positions", {
          headers: self.headers,
          data: input,
        })
      ).status(),
    ).toBe(403);
    const first = await hr.c.post("/api/v1/organisation/positions", {
      headers: hr.headers,
      data: input,
    });
    expect(first.status()).toBe(200);
    const saved = await checkResponse(
      first,
      "/api/v1/organisation/positions",
      "post",
    );
    expect(
      await checkResponse(
        await hr.c.post("/api/v1/organisation/positions", {
          headers: hr.headers,
          data: input,
        }),
        "/api/v1/organisation/positions",
        "post",
      ),
    ).toEqual(saved);
    expect(
      (
        await hr.c.post("/api/v1/organisation/positions", {
          headers: hr.headers,
          data: { ...input, title: "Different role" },
        })
      ).status(),
    ).toBe(409);
    const bad = await hr.c.post("/api/v1/organisation/positions", {
      headers: hr.headers,
      data: { ...input, code: "lower", idempotencyKey: crypto.randomUUID() },
    });
    expect(bad.status()).toBe(400);
    await checkResponse(bad, "/api/v1/organisation/positions", "post");
    expect(
      (
        await hr.c.post("/api/v1/organisation/positions", {
          headers: hr.headers,
          data: {
            ...input,
            code: `UNKNOWN-${Date.now()}`,
            departmentCode: "MISSING",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(400);
    expect(
      (await self.c.get("/api/v1/organisation/positions/history")).status(),
    ).toBe(403);
    const own = await checkResponse(
      await self.c.get(`/api/v1/organisation/positions?search=${code}&size=1`),
      "/api/v1/organisation/positions",
    );
    expect(own).toMatchObject({ total: 1 });
    const outside = await checkResponse(
      await other.c.get(`/api/v1/organisation/positions?search=${code}`),
      "/api/v1/organisation/positions",
    );
    expect(outside).toMatchObject({ total: 0 });
    expect(
      (await hr.c.get("/api/v1/organisation/positions?size=101")).status(),
    ).toBe(400);
    expect(
      (
        await hr.c.get("/api/v1/organisation/positions?direction=invalid")
      ).status(),
    ).toBe(400);
    await checkResponse(
      await hr.c.get("/api/v1/organisation/positions/history"),
      "/api/v1/organisation/positions/history",
    );
    const race = { ...input, code: `RACE-${Date.now()}` };
    const results = await Promise.all([
      hr.c.post("/api/v1/organisation/positions", {
        headers: hr.headers,
        data: { ...race, idempotencyKey: crypto.randomUUID() },
      }),
      hr.c.post("/api/v1/organisation/positions", {
        headers: hr.headers,
        data: { ...race, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
  } finally {
    await Promise.all([hr.c.dispose(), self.c.dispose(), other.c.dispose()]);
  }
});
