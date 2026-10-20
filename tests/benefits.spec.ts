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
test("HR configures a demonstration benefit; employee sees own eligibility and exact contribution", async ({
  page,
}) => {
  const code = `BEN-${Date.now()}`;
  await signIn(page, "amelia");
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
  await page
    .getByText("Benefit configuration history", { exact: true })
    .click();
  const history = page.getByRole("region", {
    name: "Benefit configuration events",
    exact: true,
  });
  await expect(history.getByRole("status")).toContainText("Creation events:");
  await page
    .getByRole("button", { name: "Create benefit plan", exact: true })
    .click();
  await page.getByLabel("Benefit plan code").fill(code);
  await page.getByLabel("Benefit plan name").fill("Synthetic wellbeing plan");
  await page.getByLabel("Monthly employee contribution").fill("25.501");
  await page
    .getByLabel("Benefit configuration reason")
    .fill("Demonstration company policy fixture");
  await page.getByRole("button", { name: "Save benefit plan" }).click();
  await expect(
    page.getByRole("alert", { name: "Benefit plan errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Benefit plan name")).toHaveValue(
    "Synthetic wellbeing plan",
  );
  await page.getByLabel("Monthly employee contribution").fill("25.50");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Save benefit plan" }).click();
  await expect(
    page.getByText("Demonstration benefit plan created and audit recorded."),
  ).toBeVisible();
  await expect(history).toHaveCount(0);
  await expect(
    page.getByText("Benefit configuration history", {
      exact: true,
    }),
  ).toHaveCount(1);
  await page.getByLabel("Search benefit plans").fill(code);
  await expect(page.locator(".benefit-card")).toContainText("£25.50");
  await page.getByText("Benefit configuration history").click();
  await expect(
    page.locator(".history-event").filter({ hasText: code }),
  ).toContainText("Demonstration company policy fixture");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await signIn(page, "sophia");
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Create benefit plan" }),
  ).toHaveCount(0);
  await page.getByLabel("Search benefit plans").fill(code);
  await page.getByLabel("Benefit eligibility").selectOption("ELIGIBLE");
  await expect(page.locator(".benefit-card")).toContainText("Eligible for you");
  await expect(page.locator(".benefit-card")).toContainText(
    "DEMO-FIXED-MONTHLY-v1",
  );
  await page.screenshot({
    path: "docs/evidence/benefits-catalogue.png",
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
test("benefit API denies configuration/history to employees and isolates tenant catalogue", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    other = await identity("outsider");
  try {
    const input = {
      code: `BEN-API-${Date.now()}`,
      name: "API demonstration plan",
      currency: "USD",
      monthlyContribution: "10.50",
      minimumServiceDays: "0",
      effectiveFrom: new Date().toISOString().slice(0, 10),
      reason: "Demo API fixture",
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await self.c.post("/api/v1/benefits/plans", {
          headers: self.headers,
          data: input,
        })
      ).status(),
    ).toBe(403);
    const created = await hr.c.post("/api/v1/benefits/plans", {
      headers: hr.headers,
      data: input,
    });
    expect(created.status()).toBe(200);
    await checkResponse(created, "/api/v1/benefits/plans", "post");
    const replay = await hr.c.post("/api/v1/benefits/plans", {
      headers: hr.headers,
      data: input,
    });
    expect(replay.status()).toBe(200);
    await checkResponse(replay, "/api/v1/benefits/plans", "post");
    expect(
      (
        await hr.c.post("/api/v1/benefits/plans", {
          headers: hr.headers,
          data: { ...input, monthlyContribution: "10.51" },
        })
      ).status(),
    ).toBe(409);
    const own = await checkResponse(
      await self.c.get(`/api/v1/benefits/plans?search=${input.code}`),
      "/api/v1/benefits/plans",
    );
    expect(own).toMatchObject({ total: 1 });
    const outside = await checkResponse(
      await other.c.get(`/api/v1/benefits/plans?search=${input.code}`),
      "/api/v1/benefits/plans",
    );
    expect(outside).toMatchObject({ total: 0 });
    expect((await self.c.get("/api/v1/benefits/history")).status()).toBe(403);
    await checkResponse(
      await hr.c.get("/api/v1/benefits/history"),
      "/api/v1/benefits/history",
    );
    expect((await self.c.get("/api/v1/benefits/plans?size=101")).status()).toBe(
      400,
    );
  } finally {
    await Promise.all([hr.c.dispose(), self.c.dispose(), other.c.dispose()]);
  }
});
