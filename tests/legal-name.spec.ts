import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
test("employee records a private legal name with independent HR approval and mobile profile; display identity is unchanged", async ({
  page,
}) => {
  const proposed = "Zoë 李 Synthetic Legal Name";
  await signIn(page, "sophia");
  const before = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as Employee;
  expect(before.legalName).toBe("");
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Not recorded");
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("legalName");
  await expect(
    page.getByText("HR approval records the supplied value", { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole("checkbox", { name: /Remove/ })).toHaveCount(0);
  await page.getByLabel("New value").fill(" ");
  await page
    .getByLabel("Reason for change")
    .fill("Employee supplied legal name for HR records");
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Reason for change")).toHaveValue(
    "Employee supplied legal name for HR records",
  );
  await page.getByLabel("New value").fill(proposed);
  const posted = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/v1/requests" &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await checkResponse(await posted, "/api/v1/requests", "post");
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "amelia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: proposed })
    .getByRole("button", { name: "View request" })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Recorded legal name");
  await page
    .getByLabel("Decision comment")
    .fill("Record supplied name; document verification remains separate");
  await page
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "sophia");
  const after = (await checkResponse(
    await page.request.get("/api/v1/employees/E003"),
    "/api/v1/employees/{id}",
  )) as Employee;
  expect(after).toMatchObject({
    legalName: proposed,
    name: before.name,
    email: before.email,
    id: before.id,
  });
  expect(
    await (await page.request.get("/api/v1/session")).json(),
  ).toMatchObject({ name: before.name, subject: "sophia" });
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Open your profile", exact: true })
    .click();
  await expect(
    page.getByRole("dialog").getByText(proposed, { exact: true }),
  ).toBeVisible();
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
  await page.screenshot({ path: "docs/evidence/legal-name-mobile.png" });
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await signIn(page, "marcus");
  await page.getByLabel("Search employees").fill("E003");
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).not.toContainText(proposed);
  expect(
    (await (await page.request.get("/api/v1/employees/E003")).json()).legalName,
  ).toBeNull();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await expect(
    page
      .getByLabel("Information to change")
      .locator('option[value="legalName"]'),
  ).toHaveCount(0);
});
test("legal-name APIs enforce private requests, history, search and exact concurrent approval with no field clearing", async () => {
  const self = await identity("sophia"),
    hr = await identity("amelia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const e = (await (
      await self.c.get("/api/v1/employees/E003")
    ).json()) as Employee;
    const command = {
      employeeId: e.id,
      field: "legalName",
      newValue: "Private Unicode 李 Name",
      effectiveDate: new Date().toISOString().slice(0, 10),
      reason: "Synthetic personal record update",
      version: e.version,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const value of ["", " ", "Invalid\nName", "x".repeat(121)])
      expect(
        (
          await self.c.post("/api/v1/requests", {
            headers: self.headers,
            data: {
              ...command,
              newValue: value,
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(400);
    for (const actor of [manager, outside])
      expect(
        (
          await actor.c.post("/api/v1/requests", {
            headers: { ...actor.headers, "X-Role": "HR", "X-Tenant": "atlas" },
            data: command,
          })
        ).status(),
      ).toBe(403);
    const made = await self.c.post("/api/v1/requests", {
      headers: self.headers,
      data: command,
    });
    expect(made.status()).toBe(200);
    const r = await made.json();
    expect(
      (
        await (
          await self.c.post("/api/v1/requests", {
            headers: self.headers,
            data: command,
          })
        ).json()
      ).id,
    ).toBe(r.id);
    for (const actor of [self, manager])
      expect(
        (
          await actor.c.post(`/api/v1/requests/${r.id}/decision`, {
            headers: actor.headers,
            data: { decision: "APPROVED", comment: "Forbidden reviewer" },
          })
        ).status(),
      ).toBe(403);
    const pending = await (
      await manager.c.get("/api/v1/request-inbox?status=SUBMITTED&size=100")
    ).json();
    expect(pending.items.some((q: { id: string }) => q.id === r.id)).toBe(
      false,
    );
    const competing = await (
      await self.c.post("/api/v1/requests", {
        headers: self.headers,
        data: {
          ...command,
          newValue: "Alternative Synthetic Legal Name",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).json();
    const results = await Promise.all(
      [r.id, competing.id].map((id) =>
        hr.c.post(`/api/v1/requests/${id}/decision`, {
          headers: hr.headers,
          data: {
            decision: "APPROVED",
            comment: "Independent supplied-name review",
          },
        }),
      ),
    );
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const loser = results[0].status() === 409 ? r.id : competing.id;
    expect(
      (
        await hr.c.post(`/api/v1/requests/${loser}/decision`, {
          headers: hr.headers,
          data: {
            decision: "REJECTED",
            comment: "Refresh after concurrent update",
          },
        })
      ).status(),
    ).toBe(200);
    expect(
      (await (await manager.c.get("/api/v1/employees/E003")).json()).legalName,
    ).toBeNull();
    const history = (await (
      await manager.c.get("/api/v1/employees/E003/history")
    ).json()) as { field: string }[];
    expect(history.some((a) => a.field === "legalName")).toBe(false);
    const current = (await (
      await self.c.get("/api/v1/employees/E003")
    ).json()) as Employee;
    expect(current.name).toBe(e.name);
    expect(current.version).toBe(e.version + 1);
    expect(
      (
        await (
          await manager.c.get(
            `/api/v1/directory?search=${encodeURIComponent(current.legalName!)}&size=12`,
          )
        ).json()
      ).total,
    ).toBe(0);
  } finally {
    for (const actor of [self, hr, manager, outside]) await actor.c.dispose();
  }
});
