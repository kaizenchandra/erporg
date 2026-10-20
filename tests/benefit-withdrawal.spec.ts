import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
async function plan() {
  const hr = await identity("amelia"),
    code = `WDR-${Date.now()}`;
  try {
    expect(
      (
        await hr.c.post("/api/v1/benefits/plans", {
          headers: hr.headers,
          data: {
            code,
            name: code,
            currency: "GBP",
            monthlyContribution: "18.50",
            minimumServiceDays: "0",
            effectiveFrom: new Date().toISOString().slice(0, 10),
            reason: "Demonstration withdrawal fixture",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await hr.c.dispose();
  }
  return code;
}
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
}
test("employee withdraws a pending benefit with preserved errors, uncertain-response replay and a fresh selection", async ({
  page,
}) => {
  const code = await plan();
  await signIn(page, "sophia");
  await page.getByLabel("Search benefit plans").fill(code);
  await page
    .getByRole("button", { name: `Select ${code}`, exact: true })
    .click();
  await page
    .getByRole("button", { name: "Add dependant", exact: true })
    .click();
  await page
    .getByLabel("Dependant 1 name")
    .fill("Withdrawal Synthetic Dependant");
  await page
    .getByLabel("Benefit selection reason")
    .fill("Withdraw this unreviewed demonstration selection later");
  await page
    .getByRole("button", { name: "Submit benefit selection", exact: true })
    .click();
  const region = page.getByRole("region", {
    name: "Benefit selections",
    exact: true,
  });
  const card = region.locator("article").filter({ hasText: code });
  await expect(card).toContainText("PENDING");
  await card
    .getByRole("button", { name: "Withdraw benefit selection", exact: true })
    .click();
  const form = page.getByRole("dialog", {
    name: "Withdraw pending benefit selection",
    exact: true,
  });
  await expect(form).toContainText(
    "No payroll deduction or provider cancellation is created",
  );
  await form
    .getByRole("button", { name: "Confirm benefit withdrawal", exact: true })
    .click();
  await expect(
    form.getByRole("alert", { name: "Benefit withdrawal errors" }),
  ).toBeFocused();
  await expect(
    form.getByRole("alert", { name: "Benefit withdrawal errors" }),
  ).toContainText("Correct the highlighted fields");
  await expect(form.getByLabel("Withdrawal reason (required)")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
  await form
    .getByLabel("Withdrawal reason (required)")
    .fill("Selection was submitted in error; retain its private history");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(form).toBeVisible();
  let interrupted = false;
  await page.route(
    "**/api/v1/benefits/selections/*/withdrawal",
    async (route) => {
      if (!interrupted) {
        interrupted = true;
        const real = await route.fetch();
        expect(real.status()).toBe(200);
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({
            detail: "Synthetic interrupted withdrawal response",
          }),
        });
      } else await route.continue();
    },
  );
  await form
    .getByRole("button", { name: "Confirm benefit withdrawal", exact: true })
    .click();
  await expect(form.getByRole("alert")).toContainText("Retry unchanged values");
  await expect(form.getByLabel("Withdrawal reason (required)")).toHaveValue(
    "Selection was submitted in error; retain its private history",
  );
  const confirmed = page.waitForResponse(
    (r) => r.url().endsWith("/withdrawal") && r.status() === 200,
  );
  await form
    .getByRole("button", { name: "Confirm benefit withdrawal", exact: true })
    .click();
  await checkResponse(
    await confirmed,
    "/api/v1/benefits/selections/{id}/withdrawal",
    "post",
  );
  await expect(form).not.toBeVisible();
  await expect(
    region.getByRole("heading", {
      name: "Your benefit selections",
      exact: true,
    }),
  ).toBeFocused();
  await expect(card).toContainText("WITHDRAWN");
  await expect(card).toContainText("Payroll handoff: Not created");
  await card
    .getByText("Dependants and selection history", { exact: true })
    .click();
  await expect(card).toContainText("Withdrawal Synthetic Dependant");
  await expect(card).toContainText("Selection was submitted in error");
  await expect(card).toContainText("Correlation:");
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
  await card.screenshot({
    path: "docs/evidence/benefit-withdrawal-mobile.png",
  });
  await page
    .getByRole("button", { name: `Select ${code}`, exact: true })
    .click();
  await page
    .getByLabel("Benefit selection reason")
    .fill("Fresh selection after retained withdrawal");
  await page
    .getByRole("button", { name: "Submit benefit selection", exact: true })
    .click();
  await expect(region.locator("article").filter({ hasText: code })).toHaveCount(
    2,
  );
});
test("benefit withdrawal APIs deny altered ownership, validate versions and arbitrate concurrent HR review", async () => {
  const self = await identity("sophia"),
    hr = await identity("amelia"),
    manager = await identity("marcus"),
    other = await identity("outsider"),
    code = await plan();
  try {
    const response = await self.c.post("/api/v1/benefits/selections", {
      headers: self.headers,
      data: {
        planCode: code,
        dependants: [],
        reason: "Race unreviewed benefit selection",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(response.status()).toBe(200);
    const p = await response.json(),
      url = `/api/v1/benefits/selections/${p.id}/withdrawal`,
      body = {
        version: 0,
        reason: "Owner withdrawal raced review",
        idempotencyKey: crypto.randomUUID(),
      };
    for (const person of [hr, manager, other])
      expect(
        (
          await person.c.post(url, {
            headers: {
              ...person.headers,
              "X-Role": "EMPLOYEE",
              "X-Employee-ID": "E003",
              "X-Tenant-ID": "atlas",
            },
            data: body,
          })
        ).status(),
      ).toBe(403);
    for (const version of [null, 0.5, -1])
      expect(
        (
          await self.c.post(url, {
            headers: self.headers,
            data: { ...body, version },
          })
        ).status(),
      ).toBe(400);
    expect(
      (
        await self.c.post(url, {
          headers: self.headers,
          data: { ...body, version: 1 },
        })
      ).status(),
    ).toBe(409);
    const results = await Promise.all([
      self.c.post(url, { headers: self.headers, data: body }),
      hr.c.post(`/api/v1/benefits/selections/${p.id}/decision`, {
        headers: hr.headers,
        data: {
          version: 0,
          outcome: "APPROVED",
          reason: "Independent competing HR review",
          idempotencyKey: crypto.randomUUID(),
        },
      }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const terminal = await results.find((r) => r.status() === 200)!.json();
    expect(terminal.history.length).toBe(2);
    expect(terminal.handoffStatus).toBe(
      terminal.status === "APPROVED" ? "PENDING_PAYROLL" : null,
    );
    if (terminal.status === "WITHDRAWN") {
      const replay = await checkResponse(
        await self.c.post(url, { headers: self.headers, data: body }),
        "/api/v1/benefits/selections/{id}/withdrawal",
        "post",
      );
      expect(replay).toEqual(terminal);
      expect(
        (
          await self.c.post(url, {
            headers: self.headers,
            data: { ...body, reason: "Conflicting reason" },
          })
        ).status(),
      ).toBe(409);
    } else
      expect(
        (
          await self.c.post(url, { headers: self.headers, data: body })
        ).status(),
      ).toBe(409);
  } finally {
    await Promise.all([self, hr, manager, other].map((p) => p.c.dispose()));
  }
});
