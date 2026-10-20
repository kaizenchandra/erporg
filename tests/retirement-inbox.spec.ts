import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
  const csrf = await (await c.get("/api/csrf")).json();
  return { c, headers: { [csrf.headerName]: csrf.token } };
}
async function signIn(page: Page, user: string) {
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: /^Approvals/ }).click();
}
async function proposal(author: Awaited<ReturnType<typeof identity>>) {
  const code = "QUEUE-" + crypto.randomUUID().slice(0, 8).toUpperCase();
  const date = new Date().toISOString().slice(0, 10);
  expect(
    (
      await author.c.post("/api/v1/legal-entities", {
        headers: author.headers,
        data: {
          entity: {
            code,
            name: "Queue reference",
            countryCode: "GB",
            currencyCode: "GBP",
            timeZone: "Europe/London",
            effectiveFrom: date,
          },
          reason: "Queue context fixture",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(200);
  const response = await author.c.post(
    `/api/v1/legal-entities/${code}/retirement/requests`,
    {
      headers: author.headers,
      data: {
        effectiveDate: date,
        reason: "Review queued reference retirement",
        version: 0,
        idempotencyKey: crypto.randomUUID(),
      },
    },
  );
  expect(response.status()).toBe(200);
  return { code, ...((await response.json()) as { id: string }) };
}
test("own retirement tracking and independent Approvals review persist decisions with accessible queue recovery", async ({
  page,
}) => {
  const author = await identity("amelia");
  try {
    const p = await proposal(author);
    await signIn(page, "amelia");
    let release: () => void = () => {};
    const held = new Promise<void>((resolve) => {
      release = resolve;
    });
    await page.route(
      "**/api/v1/reference-retirement-inbox?*",
      async (route) => {
        await held;
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ detail: "Synthetic retirement queue outage" }),
        });
      },
    );
    await page
      .getByRole("button", { name: "Reference retirements", exact: true })
      .click();
    await expect(
      page.getByText("Loading retirement queue…", { exact: true }),
    ).toBeVisible();
    release();
    await expect(
      page.getByRole("alert", { name: "Retirement queue errors" }),
    ).toBeFocused();
    await page.unroute("**/api/v1/reference-retirement-inbox?*");
    await page
      .getByRole("button", { name: "Retry retirement queue", exact: true })
      .click();
    await expect(
      page.getByText(
        "No retirement requests match. Change the scope or status to inspect retained history.",
        { exact: true },
      ),
    ).toBeVisible();
    let finishOwn: () => void = () => {};
    const delayedOwn = new Promise<void>((resolve) => {
      finishOwn = resolve;
    });
    const ownRoute = /reference-retirement-inbox\?scope=OWN&/;
    await page.route(ownRoute, async (route) => {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      await delayedOwn;
      await route.fulfill({ response }).catch(() => {});
    });
    const cancelledOwn = page.waitForEvent("requestfailed", (r) =>
      r.url().includes("reference-retirement-inbox?scope=OWN&"),
    );
    await page.getByLabel("Retirement queue scope").selectOption("OWN");
    await expect(
      page.getByText("Loading retirement queue…", { exact: true }),
    ).toBeVisible();
    await page.getByLabel("Retirement queue scope").selectOption("REVIEW");
    finishOwn();
    await cancelledOwn;
    await page.unroute(ownRoute);
    await expect(
      page.getByText(
        "No retirement requests match. Change the scope or status to inspect retained history.",
        { exact: true },
      ),
    ).toBeVisible();
    await expect(
      page.getByRole("button", {
        name: `View retirement · ${p.code} · attempt 1`,
        exact: true,
      }),
    ).toHaveCount(0);
    await page.getByLabel("Retirement queue scope").selectOption("OWN");
    const label = `View retirement · ${p.code} · attempt 1`;
    await page.getByRole("button", { name: label, exact: true }).click();
    await expect(
      page.getByRole("form", { name: "Retirement decision form" }),
    ).toHaveCount(0);
    await expect(
      page.getByText("Submitted · awaiting independent HR review.", {
        exact: true,
      }),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(
      page.getByRole("button", { name: label, exact: true }),
    ).toBeFocused();
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page.getByLabel("Workspace account")).toBeVisible();
    await signIn(page, "olivia");
    await page
      .getByRole("button", { name: "Reference retirements", exact: true })
      .click();
    await page.getByRole("button", { name: label, exact: true }).click();
    await page
      .getByLabel("Retirement review comment (required)")
      .fill("Independent queue review");
    await page
      .getByRole("button", { name: "Confirm retirement decision", exact: true })
      .click();
    await expect(
      page
        .getByRole("region", { name: "Reference retirement context" })
        .getByText(/^Retired ·/),
    ).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(
      page.getByRole("heading", {
        name: "Reference retirement requests",
        exact: true,
      }),
    ).toBeFocused();
    await expect(
      page.getByRole("button", { name: label, exact: true }),
    ).toHaveCount(0);
    await page.getByLabel("Retirement queue status").selectOption("APPROVED");
    const row = page.getByRole("article", {
      name: `Retirement request ${p.code} attempt 1`,
      exact: true,
    });
    await expect(row).toContainText("Independent queue review");
    await expect(row).toContainText("Approved");
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
      path: "docs/evidence/retirement-inbox-mobile.png",
    });
    const context = await (
      await page.request.get(`/api/v1/legal-entities/${p.code}/retirement`)
    ).json();
    expect(context.requests[0].decision.actor).toBe("olivia");
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page.getByLabel("Workspace account")).toBeVisible();
    await signIn(page, "sophia");
    await expect(
      page.getByRole("button", { name: "Reference retirements", exact: true }),
    ).toHaveCount(0);
    expect(
      (
        await page.request.get(
          "/api/v1/reference-retirement-inbox?scope=REVIEW&tenant=atlas",
        )
      ).status(),
    ).toBe(403);
  } finally {
    await author.c.dispose();
  }
});
test("retirement inbox contract scopes, filters and pages retained requests without trusting caller identity", async () => {
  const author = await identity("amelia"),
    reviewer = await identity("olivia");
  try {
    const first = await proposal(author),
      second = await proposal(author);
    const list = async (scope: string, status: string, page = 0, size = 50) => {
      const response = await reviewer.c.get(
        `/api/v1/reference-retirement-inbox?scope=${scope}&status=${status}&page=${page}&size=${size}`,
      );
      await checkResponse(response, "/api/v1/reference-retirement-inbox");
      return response.json();
    };
    const pending = await list("REVIEW", "PENDING");
    expect(
      pending.items.map((s: { request: { id: string } }) => s.request.id),
    ).toEqual(expect.arrayContaining([first.id, second.id]));
    const one = await list("REVIEW", "PENDING", 0, 1),
      two = await list("REVIEW", "PENDING", 1, 1);
    expect(one.total).toBe(pending.total);
    expect(two.total).toBe(pending.total);
    expect(one.items[0].request.id).toBe(pending.items[0].request.id);
    expect(two.items[0].request.id).toBe(pending.items[1].request.id);
    const own = await author.c.get(
      "/api/v1/reference-retirement-inbox?scope=OWN&status=PENDING&size=50",
    );
    await checkResponse(own, "/api/v1/reference-retirement-inbox");
    expect(
      (await own.json()).items.map(
        (s: { request: { id: string } }) => s.request.id,
      ),
    ).toEqual(expect.arrayContaining([first.id, second.id]));
    const url = `/api/v1/legal-entities/${first.code}/retirement/requests/${first.id}/decision`;
    const command = {
      decision: "REJECTED",
      comment: "Queue status fixture",
      version: 0,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await reviewer.c.post(url, { headers: reviewer.headers, data: command })
      ).status(),
    ).toBe(200);
    const rejected = await list("REVIEW", "REJECTED");
    expect(
      rejected.items.find(
        (s: { request: { id: string } }) => s.request.id === first.id,
      ).decision.comment,
    ).toBe(command.comment);
    expect(
      (await list("REVIEW", "PENDING")).items.some(
        (s: { request: { id: string } }) => s.request.id === first.id,
      ),
    ).toBe(false);
    for (const query of [
      "scope=all",
      "status=SUBMITTED",
      "page=-1",
      "page=4001",
      "size=0",
      "size=51",
    ])
      expect(
        (
          await reviewer.c.get(`/api/v1/reference-retirement-inbox?${query}`)
        ).status(),
      ).toBe(400);
    for (const user of ["sophia", "marcus", "outsider"]) {
      const other = await identity(user);
      try {
        expect(
          (
            await other.c.get(
              "/api/v1/reference-retirement-inbox?scope=OWN&status=ALL&tenant=atlas&subject=amelia&employeeId=E001",
            )
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
