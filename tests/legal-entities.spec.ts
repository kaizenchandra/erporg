import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
async function settings(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
}
test("HR records an unverified legal-entity reference with validation, lost-response recovery, immutable audit and mobile catalogue", async ({
  page,
}) => {
  let release: () => void = () => {};
  const hold = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/legal-entities?*", async (route) => {
    await hold;
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ detail: "Synthetic catalogue outage" }),
    });
  });
  await settings(page);
  await expect(
    page.getByText("Loading legal-entity references…", { exact: true }),
  ).toBeVisible();
  release();
  await expect(
    page.getByRole("alert", { name: "Legal-entity catalogue errors" }),
  ).toBeFocused();
  await page.unroute("**/api/v1/legal-entities?*");
  await page
    .getByRole("button", { name: "Retry legal-entity references", exact: true })
    .click();
  const section = page.getByRole("region", { name: "Legal-entity references" });
  await expect(section).toContainText("No legal-entity references recorded.");
  await page
    .getByRole("button", { name: "Create legal-entity reference", exact: true })
    .click();
  await page.getByLabel("Entity code (required)").fill("lowercase");
  await page
    .getByLabel("Declared entity name (required)")
    .fill("Synthetic Harbour 李 Ltd");
  await page.getByLabel("Country / region (required)").selectOption("GB");
  await page.getByLabel("Reference currency (required)").selectOption("GBP");
  await page
    .getByLabel("Reference time zone (required)")
    .selectOption("Europe/London");
  await page
    .getByLabel("Configuration reason (required)")
    .fill("Synthetic declared reference for later assignment");
  await page
    .getByRole("button", { name: "Record legal-entity reference", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Legal-entity reference errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Declared entity name (required)")).toHaveValue(
    "Synthetic Harbour 李 Ltd",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.screenshot({
    path: "docs/evidence/legal-entity-errors-mobile.png",
  });
  await page.getByLabel("Entity code (required)").fill("SYNTH-GB");
  let committed = "";
  await page.route("**/api/v1/legal-entities", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    committed = (await response.json()).recordedAt;
    await route.abort("failed");
  });
  await page
    .getByRole("button", { name: "Record legal-entity reference", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Legal-entity reference errors" }),
  ).toBeFocused();
  await page.unroute("**/api/v1/legal-entities");
  const posted = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/v1/legal-entities" &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Record legal-entity reference", exact: true })
    .click();
  const response = await posted;
  await checkResponse(response, "/api/v1/legal-entities", "post");
  expect((await response.json()).recordedAt).toBe(committed);
  const card = page.getByRole("article", { name: "Legal entity SYNTH-GB" });
  await expect(card).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: "Create legal-entity reference",
      exact: true,
    }),
  ).toBeFocused();
  await card.getByText("Creation audit", { exact: true }).click();
  await expect(card).toContainText("Recorded by amelia");
  await expect(section).toContainText("No employees were assigned.");
  await expect(card).toContainText("Europe/London");
  await expect(section).toContainText("1 reference");
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
  await card.scrollIntoViewIfNeeded();
  await page.screenshot({ path: "docs/evidence/legal-entity-mobile.png" });
  await page.getByLabel("Entity code or name").fill("%_");
  await page
    .getByRole("button", { name: "Search references", exact: true })
    .click();
  await expect(section).toContainText("No references match these filters.");
});
test("legal-entity APIs enforce HR/tenant scope, bounded queries, stable concurrent receipts and vocabulary validation", async () => {
  const hr = await identity("amelia");
  try {
    const options = await hr.c.get("/api/v1/legal-entities/options");
    await checkResponse(options, "/api/v1/legal-entities/options", "get");
    const payload = {
      entity: {
        code: "SYNTH-FUTURE",
        name: "Future Synthetic Entity",
        countryCode: "US",
        currencyCode: "USD",
        timeZone: "America/New_York",
        effectiveFrom: new Date(Date.now() + 86400000 * 5)
          .toISOString()
          .slice(0, 10),
      },
      reason: "Future reference only",
      idempotencyKey: crypto.randomUUID(),
    };
    const replies = await Promise.all([
      hr.c.post("/api/v1/legal-entities", {
        data: payload,
        headers: hr.headers,
      }),
      hr.c.post("/api/v1/legal-entities", {
        data: payload,
        headers: hr.headers,
      }),
    ]);
    expect(replies.map((r) => r.status())).toEqual([200, 200]);
    expect(await replies[0].json()).toEqual(await replies[1].json());
    const results = await hr.c.get(
      "/api/v1/legal-entities?status=PLANNED&size=1",
    );
    await checkResponse(results, "/api/v1/legal-entities", "get");
    expect((await results.json()).items[0].entity.code).toBe("SYNTH-FUTURE");
    expect(
      (
        await hr.c.post("/api/v1/legal-entities", {
          data: { ...payload, reason: "Conflicting payload" },
          headers: hr.headers,
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await hr.c.post("/api/v1/legal-entities", {
          data: {
            ...payload,
            idempotencyKey: crypto.randomUUID(),
            entity: {
              ...payload.entity,
              code: "UNKNOWN",
              countryCode: "ZZ",
              currencyCode: "ZZZ",
              timeZone: "Unknown/Zone",
            },
          },
          headers: hr.headers,
        })
      ).status(),
    ).toBe(400);
    expect((await hr.c.get("/api/v1/legal-entities?size=51")).status()).toBe(
      400,
    );
    expect((await hr.c.get("/api/v1/legal-entities?status=BAD")).status()).toBe(
      400,
    );
    for (const user of ["sophia", "marcus", "outsider"]) {
      const other = await identity(user);
      try {
        expect(
          (await other.c.get("/api/v1/legal-entities?tenant=atlas")).status(),
        ).toBe(403);
        expect(
          (await other.c.get("/api/v1/legal-entities/options")).status(),
        ).toBe(403);
        expect(
          (
            await other.c.post("/api/v1/legal-entities", {
              headers: other.headers,
              data: payload,
            })
          ).status(),
        ).toBe(403);
      } finally {
        await other.c.dispose();
      }
    }
  } finally {
    await hr.c.dispose();
  }
});

test("reference catalogue pages and filters real records while employee Settings shows no HR actions", async ({
  page,
}) => {
  const hr = await identity("amelia");
  try {
    for (let n = 0; n < 11; n++)
      expect(
        (
          await hr.c.post("/api/v1/legal-entities", {
            headers: hr.headers,
            data: {
              entity: {
                code: `PAGE-${String(n).padStart(2, "0")}`,
                name: "Pagination reference",
                countryCode: "US",
                currencyCode: "USD",
                timeZone: "UTC",
                effectiveFrom: new Date().toISOString().slice(0, 10),
              },
              reason: "Synthetic pagination fixture",
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(200);
  } finally {
    await hr.c.dispose();
  }
  await settings(page);
  const region = page.getByRole("region", { name: "Legal-entity references" });
  await expect(region).toContainText("13 references");
  await page
    .getByRole("button", { name: "Next references", exact: true })
    .click();
  await expect(region).toContainText("page 2");
  await expect(
    page.getByRole("article", { name: "Legal entity PAGE-10", exact: true }),
  ).toBeVisible();
  await page.getByLabel("Entity code or name").fill("PAGE-");
  await page
    .getByRole("button", { name: "Search references", exact: true })
    .click();
  await expect(region).toContainText("11 references · page 1");
  await page.getByLabel("Reference availability").selectOption("PLANNED");
  await expect(region).toContainText("No references match these filters.");
  await page.getByLabel("Reference availability").selectOption("CURRENT");
  await expect(region).toContainText("11 references");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.getByLabel("Workspace account").selectOption("sophia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Settings", exact: true }).click();
  await expect(
    page.getByText(
      "HR permission is required for legal-entity reference settings.",
    ),
  ).toBeVisible();
  await expect(
    page.getByRole("button", {
      name: "Create legal-entity reference",
      exact: true,
    }),
  ).toHaveCount(0);
});
