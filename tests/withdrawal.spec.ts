import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("sophia");
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
}
async function identity(user: string) {
  const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  let t = await (await c.get("/api/csrf")).json();
  expect(
    (
      await c.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [t.headerName]: t.token },
      })
    ).status(),
  ).toBe(204);
  t = await (await c.get("/api/csrf")).json();
  return { c, headers: { [t.headerName]: t.token } };
}
test("employee submits then withdraws pending change with preserved validation and audit", async ({
  page,
}) => {
  await signIn(page);
  const before = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  const proposed = `+44 7700 ${Date.now().toString().slice(-6)}`;
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("New value").fill(proposed);
  await page
    .getByLabel("Reason for change")
    .fill("Synthetic withdrawal UI fixture");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: proposed })
    .getByRole("button", { name: "Withdraw request" })
    .click();
  await page.getByRole("button", { name: "Confirm withdrawal" }).click();
  await expect(
    page.getByRole("alert", { name: "Withdrawal errors" }),
  ).toBeFocused();
  await page
    .getByLabel("Withdrawal reason")
    .fill("Incorrect contact number entered");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Confirm withdrawal" }).click();
  await expect(
    page.getByText("Request withdrawn. No employee change was applied."),
  ).toBeVisible();
  await expect(
    page
      .locator(".request-card")
      .filter({ hasText: proposed })
      .locator(".badge"),
  ).toHaveText("Withdrawn");
  const after = await checkResponse(
    await page.request.get("/api/v1/employees/E003"),
    "/api/v1/employees/{id}",
  );
  expect(after).toMatchObject({ phone: before.phone, version: before.version });
  await page.screenshot({
    path: "docs/evidence/withdrawn-request.png",
    fullPage: true,
  });
  const audit = await (
    await page.request.get("/api/v1/employees/E003/history")
  ).json();
  expect(
    audit.some(
      (a: { action: string; reason: string }) =>
        a.action === "WITHDRAWN" &&
        a.reason === "Incorrect contact number entered",
    ),
  ).toBe(true);
});
test("API requires explicit version and safely arbitrates approval versus owner withdrawal", async () => {
  const self = await identity("sophia"),
    hr = await identity("amelia"),
    manager = await identity("marcus"),
    other = await identity("outsider");
  try {
    const e = await (await self.c.get("/api/v1/employees/E003")).json();
    const input = {
      employeeId: e.id,
      field: "phone",
      newValue: `+44 7700 ${Date.now().toString().slice(-6)}`,
      effectiveDate: new Date().toISOString().slice(0, 10),
      reason: "Withdrawal race fixture",
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await self.c.post("/api/v1/requests", {
          headers: self.headers,
          data: input,
        })
      ).status(),
    ).toBe(400);
    expect(
      (
        await self.c.post("/api/v1/requests", {
          headers: self.headers,
          data: { ...input, version: null },
        })
      ).status(),
    ).toBe(400);
    const r = await self.c.post("/api/v1/requests", {
      headers: self.headers,
      data: { ...input, version: e.version },
    });
    expect(r.status()).toBe(200);
    const pending = await r.json();
    const withdrawal = {
      reason: "Cancel incorrect fixture",
      idempotencyKey: crypto.randomUUID(),
    };
    for (const actor of [hr, manager, other])
      expect(
        (
          await actor.c.post(`/api/v1/requests/${pending.id}/withdraw`, {
            headers: actor.headers,
            data: withdrawal,
          })
        ).status(),
      ).toBe(403);
    const results = await Promise.all([
      self.c.post(`/api/v1/requests/${pending.id}/withdraw`, {
        headers: self.headers,
        data: withdrawal,
      }),
      hr.c.post(`/api/v1/requests/${pending.id}/decision`, {
        headers: hr.headers,
        data: { decision: "APPROVED", comment: "Independent race approval" },
      }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const current = await (await self.c.get("/api/v1/employees/E003")).json();
    if (results[0].status() === 200) {
      expect(current).toMatchObject({ phone: e.phone, version: e.version });
      const replay = await self.c.post(
        `/api/v1/requests/${pending.id}/withdraw`,
        { headers: self.headers, data: withdrawal },
      );
      expect(replay.status()).toBe(200);
      expect(
        await checkResponse(replay, "/api/v1/requests/{id}/withdraw", "post"),
      ).toMatchObject({ id: pending.id, status: "WITHDRAWN" });
      expect(
        (
          await self.c.post(`/api/v1/requests/${pending.id}/withdraw`, {
            headers: self.headers,
            data: { ...withdrawal, reason: "Changed reason" },
          })
        ).status(),
      ).toBe(409);
    } else {
      expect(current).toMatchObject({
        phone: input.newValue,
        version: e.version + 1,
      });
    }
  } finally {
    await Promise.all([
      self.c.dispose(),
      hr.c.dispose(),
      manager.c.dispose(),
      other.c.dispose(),
    ]);
  }
});
