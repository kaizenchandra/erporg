import { test, expect, request, type Page } from "@playwright/test";
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
async function restoreName(name: string) {
  const hr = await identity("amelia"),
    review = await identity("olivia");
  try {
    const e = await (await hr.c.get("/api/v1/employees/E003")).json();
    if (e.name === name) return;
    const r = await hr.c.post("/api/v1/requests", {
      headers: hr.headers,
      data: {
        employeeId: e.id,
        field: "name",
        newValue: name,
        effectiveDate: new Date().toISOString().slice(0, 10),
        reason: "Restore synthetic display name",
        version: e.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(r.status()).toBe(200);
    const pending = await r.json();
    expect(
      (
        await review.c.post(`/api/v1/requests/${pending.id}/decision`, {
          headers: review.headers,
          data: {
            decision: "APPROVED",
            comment: "Independent fixture restoration",
          },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await hr.c.dispose();
    await review.c.dispose();
  }
}
test("employee requests display name, HR approves and effective profile/search/session name persist", async ({
  page,
}) => {
  const proposed = `Zoë Synthetic ${Date.now()}`;
  let original = "Sophia Martinez";
  try {
    await signIn(page, "sophia");
    const before = await (
      await page.request.get("/api/v1/employees/E003")
    ).json();
    original = before.name;
    await page
      .getByRole("button", { name: `View ${original}`, exact: true })
      .click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Request a change", exact: true })
      .click();
    await page.getByLabel("Information to change").selectOption("name");
    await page.getByLabel("New value").fill(" ");
    await page
      .getByLabel("Reason for change")
      .fill("Employee display name request");
    await page.getByRole("button", { name: "Submit for approval" }).click();
    await expect(
      page.getByRole("alert", { name: "Change request errors" }),
    ).toBeFocused();
    await expect(page.getByLabel("Reason for change")).toHaveValue(
      "Employee display name request",
    );
    await page.getByLabel("New value").fill(proposed);
    expect(
      (
        await new AxeBuilder({ page })
          .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
          .analyze()
      ).violations,
    ).toEqual([]);
    await page.getByRole("button", { name: "Submit for approval" }).click();
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
    await page
      .getByLabel("Decision comment")
      .fill("HR confirmed preferred display name");
    await page.getByRole("button", { name: "Confirm approval" }).click();
    await expect(
      page.getByText("Decision recorded. The requester has been notified."),
    ).toBeVisible();
    await signOut(page);
    await signIn(page, "sophia");
    expect(
      await checkResponse(
        await page.request.get("/api/v1/session"),
        "/api/v1/session",
      ),
    ).toMatchObject({ name: proposed, subject: "sophia", employeeId: "E003" });
    await page.getByLabel("Search employees").fill(proposed);
    await page
      .getByRole("button", { name: `View ${proposed}`, exact: true })
      .click();
    await expect(
      page.getByRole("dialog").getByText(proposed, { exact: true }),
    ).toBeVisible();
    await expect(
      page
        .locator(".history-event")
        .filter({ hasText: proposed })
        .filter({ hasText: "approved" }),
    ).toBeVisible();
    const after = await checkResponse(
      await page.request.get("/api/v1/employees/E003"),
      "/api/v1/employees/{id}",
    );
    expect(after).toMatchObject({
      name: proposed,
      id: before.id,
      email: before.email,
    });
    await page.screenshot({
      path: "docs/evidence/display-name-profile.png",
      fullPage: true,
    });
  } finally {
    await restoreName(original);
  }
});
test("name API denies manager edits/review and self review; concurrent approvals have one winner", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus");
  let original = "Sophia Martinez";
  try {
    const e = await (await self.c.get("/api/v1/employees/E003")).json();
    original = e.name;
    const input = {
      employeeId: e.id,
      field: "name",
      newValue: `API Name ${Date.now()}`,
      effectiveDate: new Date().toISOString().slice(0, 10),
      reason: "Name concurrency fixture",
      version: e.version,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await manager.c.post("/api/v1/requests", {
          headers: manager.headers,
          data: input,
        })
      ).status(),
    ).toBe(403);
    const first = await self.c.post("/api/v1/requests", {
      headers: self.headers,
      data: input,
    });
    expect(first.status()).toBe(200);
    const a = await first.json();
    expect(
      (
        await manager.c.post(`/api/v1/requests/${a.id}/decision`, {
          headers: manager.headers,
          data: { decision: "APPROVED", comment: "Forbidden manager review" },
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await self.c.post(`/api/v1/requests/${a.id}/decision`, {
          headers: self.headers,
          data: { decision: "APPROVED", comment: "Forbidden self review" },
        })
      ).status(),
    ).toBe(403);
    const second = await self.c.post("/api/v1/requests", {
      headers: self.headers,
      data: {
        ...input,
        newValue: `Alternative ${Date.now()}`,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(second.status()).toBe(200);
    const b = await second.json();
    const results = await Promise.all([
      hr.c.post(`/api/v1/requests/${a.id}/decision`, {
        headers: hr.headers,
        data: { decision: "APPROVED", comment: "Independent review A" },
      }),
      hr.c.post(`/api/v1/requests/${b.id}/decision`, {
        headers: hr.headers,
        data: { decision: "APPROVED", comment: "Independent review B" },
      }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const loser = results[0].status() === 409 ? a : b;
    expect(
      (
        await hr.c.post(`/api/v1/requests/${loser.id}/decision`, {
          headers: hr.headers,
          data: {
            decision: "REJECTED",
            comment: "Stale name request; resubmit",
          },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await Promise.all([hr.c.dispose(), self.c.dispose(), manager.c.dispose()]);
    await restoreName(original);
  }
});
