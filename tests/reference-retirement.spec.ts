import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function signIn(page: Page, user: string) {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
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
test("HR requests retirement with read/error and lost-response recovery; independent HR approves retained mobile history", async ({
  page,
}) => {
  await signIn(page, "amelia");
  await page.route("**/api/v1/legal-entities/PAGE-00/retirement", (route) =>
    route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ detail: "Synthetic retirement context outage" }),
    }),
  );
  await page
    .getByRole("button", { name: "Manage retirement · PAGE-00", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Retirement context errors" }),
  ).toBeFocused();
  await page.unroute("**/api/v1/legal-entities/PAGE-00/retirement");
  await page
    .getByRole("button", { name: "Retry retirement context", exact: true })
    .click();
  await page.getByLabel("Retirement reason (required)").fill(" ");
  await page
    .getByRole("button", { name: "Submit retirement for review", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Retirement command errors" }),
  ).toBeFocused();
  await page
    .getByLabel("Retirement reason (required)")
    .fill("Retire unused synthetic reference");
  let committed = "";
  await page.route(
    "**/api/v1/legal-entities/PAGE-00/retirement/requests",
    async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      committed = (await response.json()).id;
      await route.abort("failed");
    },
  );
  await page
    .getByRole("button", { name: "Submit retirement for review", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Retirement command errors" }),
  ).toBeFocused();
  await page.unroute("**/api/v1/legal-entities/PAGE-00/retirement/requests");
  const posted = page.waitForResponse(
    (r) =>
      r.url().endsWith("/PAGE-00/retirement/requests") &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Submit retirement for review", exact: true })
    .click();
  const response = await posted;
  await checkResponse(
    response,
    "/api/v1/legal-entities/{code}/retirement/requests",
    "post",
  );
  expect((await response.json()).id).toBe(committed);
  await expect(
    page.getByText("Submitted · awaiting independent HR review."),
  ).toBeVisible();
  await expect(
    page.getByRole("form", { name: "Retirement decision form" }),
  ).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(
    page.getByRole("button", {
      name: "Manage retirement · PAGE-00",
      exact: true,
    }),
  ).toBeFocused();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await signIn(page, "olivia");
  await page
    .getByRole("button", { name: "Manage retirement · PAGE-00", exact: true })
    .click();
  await page
    .getByText("Original reference and creation audit", { exact: true })
    .click();
  await page.getByLabel("Retirement review comment (required)").fill(" ");
  await page
    .getByRole("button", { name: "Confirm retirement decision", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Retirement command errors" }),
  ).toBeFocused();
  await page
    .getByLabel("Retirement review comment (required)")
    .fill("Independent retirement review; no employee effects");
  await page
    .getByRole("button", { name: "Confirm retirement decision", exact: true })
    .click();
  await expect(
    page
      .getByRole("region", { name: "Reference retirement context" })
      .getByText(/^Retired ·/),
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
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "docs/evidence/reference-retirement-mobile.png",
  });
  const context = await page.request.get(
    "/api/v1/legal-entities/PAGE-00/retirement",
  );
  await checkResponse(
    context,
    "/api/v1/legal-entities/{code}/retirement",
    "get",
  );
  const state = await context.json();
  expect(state.requests).toHaveLength(1);
  expect(state.requests[0].decision.actor).toBe("olivia");
  expect(state.reference.createdBy).toBe("amelia");
  await page.keyboard.press("Escape");
  await page.getByLabel("Reference availability").selectOption("RETIRED");
  await expect(
    page.getByRole("article", { name: "Legal entity PAGE-00", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("article", { name: "Legal entity PAGE-01", exact: true }),
  ).toHaveCount(0);
});
test("retirement API enforces independent scope, exact versions, replay and concurrent review; future date stays available", async () => {
  const author = await identity("amelia"),
    reviewer = await identity("olivia");
  const base = "/api/v1/legal-entities/PAGE-01/retirement";
  try {
    const command = {
      effectiveDate: new Date(Date.now() + 86400000 * 7)
        .toISOString()
        .slice(0, 10),
      reason: "Future synthetic reference retirement",
      version: 0,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await author.c.post(base + "/requests", {
          headers: author.headers,
          data: { ...command, version: 0.5 },
        })
      ).status(),
    ).toBe(400);
    const first = await author.c.post(base + "/requests", {
      headers: author.headers,
      data: command,
    });
    expect(first.status()).toBe(200);
    const p = await first.json();
    expect(
      (
        await (
          await author.c.post(base + "/requests", {
            headers: author.headers,
            data: command,
          })
        ).json()
      ).id,
    ).toBe(p.id);
    const decision = {
        decision: "APPROVED",
        comment: "Independent future retirement",
        version: 0,
        idempotencyKey: crypto.randomUUID(),
      },
      url = base + `/requests/${p.id}/decision`;
    expect(
      (
        await author.c.post(url, { headers: author.headers, data: decision })
      ).status(),
    ).toBe(403);
    expect(
      (
        await reviewer.c.post(url, {
          headers: reviewer.headers,
          data: { ...decision, version: null },
        })
      ).status(),
    ).toBe(400);
    const competing = { ...decision, idempotencyKey: crypto.randomUUID() };
    const results = await Promise.all([
      reviewer.c.post(url, { headers: reviewer.headers, data: decision }),
      reviewer.c.post(url, { headers: reviewer.headers, data: competing }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const winner = await results.find((r) => r.status() === 200)!.json();
    const replay = await reviewer.c.post(url, {
      headers: reviewer.headers,
      data: results[0].status() === 200 ? decision : competing,
    });
    expect(replay.status()).toBe(200);
    expect(await replay.json()).toEqual(winner);
    const available = await author.c.get(
      "/api/v1/legal-entities?search=PAGE-01&status=CURRENT",
    );
    await checkResponse(available, "/api/v1/legal-entities", "get");
    expect((await available.json()).total).toBe(1);
    expect(
      (
        await (
          await author.c.get(
            "/api/v1/legal-entities?search=PAGE-01&status=RETIRED",
          )
        ).json()
      ).total,
    ).toBe(0);
    expect(
      (
        await (
          await author.c.post(base + "/requests", {
            headers: author.headers,
            data: command,
          })
        ).json()
      ).id,
    ).toBe(p.id);
    for (const user of ["sophia", "marcus", "outsider"]) {
      const other = await identity(user);
      try {
        expect((await other.c.get(base + "?tenant=atlas")).status()).toBe(403);
        expect(
          (
            await other.c.post(base + "/requests", {
              headers: other.headers,
              data: command,
            })
          ).status(),
        ).toBe(403);
        expect(
          (
            await other.c.post(url, { headers: other.headers, data: decision })
          ).status(),
        ).toBe(403);
      } finally {
        await other.c.dispose();
      }
    }
  } finally {
    await author.c.dispose();
    await reviewer.c.dispose();
  }
});
