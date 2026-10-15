import { test, expect, type Page, request } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
}
async function signOut(page: Page) {
  if (await page.getByRole("dialog").count())
    await page.getByRole("button", { name: "Close panel" }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Enter workspace" }),
  ).toBeVisible();
}
async function openChange(page: Page) {
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("positionCode");
}
async function approve(page: Page, cardText: string) {
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: cardText })
    .filter({ hasText: "Primary position" })
    .getByRole("button", { name: "View request" })
    .first()
    .click();
  await page
    .getByLabel("Decision comment")
    .fill("HR verified primary position scope");
  await page.getByRole("button", { name: "Confirm approval" }).click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
}
test("manager requests a primary position; HR approves; employee sees history; unassignment restores empty scope", async ({
  page,
}) => {
  await signIn(page, "amelia");
  const employee = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  const departments = await (
    await page.request.get("/api/v1/organisation/departments")
  ).json();
  const department = departments.find(
    (d: { name: string }) => d.name === employee.department,
  );
  const code = `ASSIGN-${Date.now()}`;
  const token = await (await page.request.get("/api/csrf")).json();
  const position = await page.request.post("/api/v1/organisation/positions", {
    headers: { [token.headerName]: token.token },
    data: {
      code,
      title: "Synthetic primary position",
      departmentCode: department.code,
      effectiveFrom: new Date().toISOString().slice(0, 10),
      reason: "Assignment UI fixture",
      idempotencyKey: crypto.randomUUID(),
    },
  });
  expect(position.status()).toBe(200);
  await signOut(page);
  await signIn(page, "marcus");
  await openChange(page);
  await page.getByLabel("Find a position").fill(code);
  await page.getByLabel("New value").selectOption(code);
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("New value")).toHaveValue(code);
  await page
    .getByLabel("Reason for change")
    .fill("Assign approved departmental position");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.screenshot({
    path: "docs/evidence/position-assignment-form.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  const profile = await checkResponse(
    await page.request.get("/api/v1/employees/E003"),
    "/api/v1/employees/{id}",
  );
  expect(profile).toMatchObject({ positionCode: employee.positionCode });
  await signOut(page);
  await signIn(page, "amelia");
  await approve(page, code);
  await signOut(page);
  await signIn(page, "sophia");
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await expect(
    page
      .locator(".detail-grid > div")
      .filter({ has: page.locator("dt", { hasText: "Primary position" }) })
      .locator("dd"),
  ).toHaveText(code);
  await expect(
    page
      .locator(".history-event")
      .filter({ hasText: code })
      .filter({ hasText: "approved" })
      .first(),
  ).toBeVisible();
  await page.screenshot({
    path: "docs/evidence/assigned-position-profile.png",
    fullPage: true,
  });
  await signOut(page);
  await signIn(page, "marcus");
  await openChange(page);
  await page.getByLabel("Unassign the current position").check();
  await expect(page.getByLabel("New value")).toBeDisabled();
  await page
    .getByLabel("Reason for change")
    .fill("End synthetic primary assignment");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "amelia");
  await approve(page, "Unassign position");
  const cleared = await checkResponse(
    await page.request.get("/api/v1/employees/E003"),
    "/api/v1/employees/{id}",
  );
  expect(cleared).toMatchObject({ positionCode: "", title: employee.title });
});
test("direct assignment API denies self/tenant access and concurrent approvals commit once", async () => {
  async function identity(user: string) {
    const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
    let token = await (await c.get("/api/csrf")).json();
    expect(
      (
        await c.post("/api/login", {
          form: { username: user, password: "atlas-demo" },
          headers: { [token.headerName]: token.token },
        })
      ).status(),
    ).toBe(204);
    token = await (await c.get("/api/csrf")).json();
    return { c, headers: { [token.headerName]: token.token } };
  }
  const hr = await identity("amelia"),
    second = await identity("olivia"),
    self = await identity("sophia"),
    outsider = await identity("outsider");
  try {
    const employee = await (await hr.c.get("/api/v1/employees/E003")).json();
    const depts = await (
      await hr.c.get("/api/v1/organisation/departments")
    ).json();
    const dept = depts.find(
      (d: { name: string }) => d.name === employee.department,
    );
    const code = `API-ASSIGN-${Date.now()}`;
    const date = new Date().toISOString().slice(0, 10);
    expect(
      (
        await hr.c.post("/api/v1/organisation/positions", {
          headers: hr.headers,
          data: {
            code,
            title: "API assignment role",
            departmentCode: dept.code,
            effectiveFrom: date,
            reason: "Direct assignment fixture",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    const input = {
      employeeId: "E003",
      field: "positionCode",
      newValue: code,
      effectiveDate: date,
      reason: "Direct assignment",
      version: employee.version,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await self.c.post("/api/v1/requests", {
          headers: self.headers,
          data: input,
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await outsider.c.post("/api/v1/requests", {
          headers: outsider.headers,
          data: input,
        })
      ).status(),
    ).toBe(403);
    const pending = await hr.c.post("/api/v1/requests", {
      headers: hr.headers,
      data: input,
    });
    expect(pending.status()).toBe(200);
    const r = await pending.json();
    await checkResponse(pending, "/api/v1/requests", "post");
    expect(
      (
        await hr.c.post(`/api/v1/requests/${r.id}/decision`, {
          headers: hr.headers,
          data: { decision: "APPROVED", comment: "Self review forbidden" },
        })
      ).status(),
    ).toBe(403);
    const results = await Promise.all([
      second.c.post(`/api/v1/requests/${r.id}/decision`, {
        headers: second.headers,
        data: { decision: "APPROVED", comment: "Independent review one" },
      }),
      second.c.post(`/api/v1/requests/${r.id}/decision`, {
        headers: second.headers,
        data: { decision: "APPROVED", comment: "Independent review two" },
      }),
    ]);
    expect(results.map((x) => x.status()).sort()).toEqual([200, 409]);
    const assigned = await (await self.c.get("/api/v1/employees/E003")).json();
    expect(assigned.positionCode).toBe(code);
    expect(assigned.version).toBe(employee.version + 1);
    const clear = await hr.c.post("/api/v1/requests", {
      headers: hr.headers,
      data: {
        ...input,
        newValue: "",
        version: assigned.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(clear.status()).toBe(200);
    const cr = await clear.json();
    expect(
      (
        await second.c.post(`/api/v1/requests/${cr.id}/decision`, {
          headers: second.headers,
          data: { decision: "APPROVED", comment: "End fixture assignment" },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await Promise.all([
      hr.c.dispose(),
      second.c.dispose(),
      self.c.dispose(),
      outsider.c.dispose(),
    ]);
  }
});
