import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
}
async function plan() {
  const hr = await identity("amelia"),
    code = `SEL-${Date.now()}`;
  try {
    expect(
      (
        await hr.c.post("/api/v1/benefits/plans", {
          headers: hr.headers,
          data: {
            code,
            name: code,
            currency: "GBP",
            monthlyContribution: "35.50",
            minimumServiceDays: "0",
            effectiveFrom: new Date().toISOString().slice(0, 10),
            reason: "Demonstration selection fixture",
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
test("employee submits dependant selection; independent HR review queues one payroll handoff", async ({
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
  await page.getByLabel("Dependant 1 name").fill("Synthetic Family Member");
  await page.getByRole("button", { name: "Submit benefit selection" }).click();
  await expect(
    page.getByRole("alert", { name: "Benefit selection errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Dependant 1 name")).toHaveValue(
    "Synthetic Family Member",
  );
  await page
    .getByLabel("Benefit selection reason")
    .fill("Demonstration family cover");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Submit benefit selection" }).click();
  const item = page
    .getByRole("region", { name: "Benefit selections" })
    .locator("article")
    .filter({ hasText: code });
  await expect(item).toContainText("PENDING");
  await item.getByText("Dependants and selection history").click();
  await expect(item).toContainText("Synthetic Family Member");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await signIn(page, "amelia");
  const review = page
    .getByRole("region", { name: "Benefit selections" })
    .locator("article")
    .filter({ hasText: code });
  await review
    .getByRole("button", { name: "Review benefit selection" })
    .click();
  await expect(page.getByRole("dialog")).toContainText("35.50");
  await page
    .getByLabel("Benefit selection reason")
    .fill("Independent demo policy review");
  await page.getByRole("button", { name: "Confirm benefit decision" }).click();
  await expect(review).toContainText("APPROVED");
  await expect(review).toContainText("PENDING_PAYROLL");
  await review.getByText("Dependants and selection history").click();
  await expect(review).toContainText("Independent demo policy review");
  await page.screenshot({
    path: "docs/evidence/benefit-selection-approved.png",
    fullPage: true,
  });
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
test("selection API contracts, privacy, duplicate recovery and competing decisions", async () => {
  const code = await plan(),
    hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const input = {
      planCode: code,
      dependants: [{ name: "Synthetic Child", relationship: "CHILD" }],
      reason: "Demo selection API",
      idempotencyKey: crypto.randomUUID(),
    };
    const [a, b] = await Promise.all([
      self.c.post("/api/v1/benefits/selections", {
        headers: self.headers,
        data: input,
      }),
      self.c.post("/api/v1/benefits/selections", {
        headers: self.headers,
        data: input,
      }),
    ]);
    expect([a.status(), b.status()]).toEqual([200, 200]);
    const pending = await a.json();
    expect((await b.json()).id).toBe(pending.id);
    await checkResponse(a, "/api/v1/benefits/selections", "post");
    expect(
      (
        await self.c.post("/api/v1/benefits/selections", {
          headers: self.headers,
          data: { ...input, reason: "Conflicting replay" },
        })
      ).status(),
    ).toBe(409);
    const decision = {
      version: 0,
      outcome: "APPROVED",
      reason: "Independent review",
      idempotencyKey: crypto.randomUUID(),
    };
    for (const denied of [self, manager, outside])
      expect(
        (
          await denied.c.post(
            `/api/v1/benefits/selections/${pending.id}/decision`,
            { headers: denied.headers, data: decision },
          )
        ).status(),
      ).toBe(403);
    for (const denied of [manager, outside]) {
      const result = await denied.c.get("/api/v1/benefits/selections?size=100");
      await checkResponse(result, "/api/v1/benefits/selections");
      expect(
        (await result.json()).items.some(
          (x: { id: string }) => x.id === pending.id,
        ),
      ).toBe(false);
    }
    const [approved, rejected] = await Promise.all([
      hr.c.post(`/api/v1/benefits/selections/${pending.id}/decision`, {
        headers: hr.headers,
        data: decision,
      }),
      reviewer.c.post(`/api/v1/benefits/selections/${pending.id}/decision`, {
        headers: reviewer.headers,
        data: {
          ...decision,
          outcome: "REJECTED",
          idempotencyKey: crypto.randomUUID(),
        },
      }),
    ]);
    expect([approved.status(), rejected.status()].sort()).toEqual([200, 409]);
    const winner = approved.status() === 200 ? approved : rejected;
    await checkResponse(
      winner,
      "/api/v1/benefits/selections/{id}/decision",
      "post",
    );
    const final = await winner.json();
    expect(final.history).toHaveLength(2);
    expect(final.handoffStatus).toBe(
      final.status === "APPROVED" ? "PENDING_PAYROLL" : null,
    );
    const own = await hr.c.post("/api/v1/benefits/selections", {
      headers: hr.headers,
      data: { ...input, idempotencyKey: crypto.randomUUID() },
    });
    expect(own.status()).toBe(200);
    expect(
      (
        await hr.c.post(
          `/api/v1/benefits/selections/${(await own.json()).id}/decision`,
          { headers: hr.headers, data: decision },
        )
      ).status(),
    ).toBe(403);
    expect(
      (await self.c.get("/api/v1/benefits/selections?size=101")).status(),
    ).toBe(400);
  } finally {
    await Promise.all([
      hr.c.dispose(),
      reviewer.c.dispose(),
      self.c.dispose(),
      manager.c.dispose(),
      outside.c.dispose(),
    ]);
  }
});
