import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse, schemaValidator } from "./support/contract";
import type { SelectionPage } from "../features/benefits/selections";
const path = "/api/v1/benefits/selection-inbox";
async function identity(user: string) {
  const client = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  let token = (await (await client.get("/api/csrf")).json()) as {
    token: string;
    headerName: string;
  };
  expect(
    (
      await client.post("/api/login", {
        headers: { [token.headerName]: token.token },
        form: { username: user, password: "atlas-demo" },
      })
    ).status(),
  ).toBe(204);
  token = (await (await client.get("/api/csrf")).json()) as {
    token: string;
    headerName: string;
  };
  return { client, headers: { [token.headerName]: token.token } };
}
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
}
async function queue(page: Page) {
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .getByRole("button", { name: "Benefit selections", exact: true })
    .click();
  return page.getByRole("region", {
    name: "Benefit selection approval queue",
    exact: true,
  });
}
test("employee submission is independently approved through the scoped benefit inbox and retained in own tracking", async ({
  page,
}) => {
  test.setTimeout(60000);
  const hr = await identity("amelia"),
    self = await identity("sophia");
  try {
    for (let i = 0; i < 14; i++) {
      const code = `BG-Q${String(i).padStart(2, "0")}`;
      expect(
        (
          await hr.client.post("/api/v1/benefits/plans", {
            headers: hr.headers,
            data: {
              code,
              name: code,
              currency: "GBP",
              monthlyContribution: "15.50",
              minimumServiceDays: "0",
              effectiveFrom: new Date().toISOString().slice(0, 10),
              reason: "Synthetic BG queue fixture",
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(200);
      if (i < 13) {
        const actor = i === 12 ? hr : self;
        expect(
          (
            await actor.client.post("/api/v1/benefits/selections", {
              headers: actor.headers,
              data: {
                planCode: code,
                dependants:
                  i === 0
                    ? [
                        {
                          name: "Synthetic Family Member",
                          relationship: "CHILD",
                        },
                      ]
                    : [],
                reason:
                  i === 0
                    ? "Synthetic long queue reason " + "界".repeat(250)
                    : "Synthetic BG pending queue",
                idempotencyKey: crypto.randomUUID(),
              },
            })
          ).status(),
        ).toBe(200);
      }
    }
  } finally {
    await hr.client.dispose();
    await self.client.dispose();
  }
  await login(page, "sophia");
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
  await page.getByLabel("Search benefit plans", { exact: true }).fill("BG-Q13");
  await page
    .getByRole("button", { name: "Select BG-Q13", exact: true })
    .click();
  await page
    .getByRole("textbox", { name: /^Benefit selection reason/ })
    .fill("Synthetic BG employee request through Approvals");
  await page
    .getByRole("button", { name: "Submit benefit selection", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  let panel = await queue(page);
  await expect(
    panel.getByRole("combobox", { name: /^Benefit queue scope/ }),
  ).toHaveValue("OWN");
  await expect(panel.locator("article").first()).toContainText("BG-Q13");
  await expect(
    panel.getByRole("button", { name: /^Review benefit request/ }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await login(page, "amelia");
  panel = await queue(page);
  await expect(
    panel.getByRole("combobox", { name: /^Benefit queue scope/ }),
  ).toHaveValue("REVIEW");
  await expect(panel.locator("article")).toHaveCount(8);
  await panel
    .getByRole("button", { name: "Next benefit queue page", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(
    panel.getByRole("heading", {
      name: "Benefit selection requests",
      exact: true,
    }),
  ).toBeFocused();
  await expect(panel.getByRole("status")).toContainText("Page 2");
  await panel
    .getByRole("combobox", { name: /^Benefit queue rows per page/ })
    .selectOption("32");
  await expect(panel.getByRole("status")).toContainText("Page 1");
  await expect(
    panel.locator("article").filter({ hasText: "BG-Q12" }),
  ).toHaveCount(0);
  const item = panel.locator("article").filter({ hasText: "BG-Q13" });
  await item
    .getByRole("button", {
      name: "Review benefit request E003 · BG-Q13",
      exact: true,
    })
    .click();
  await expect(page.getByRole("dialog")).toContainText("15.50");
  await page
    .getByRole("button", { name: "Confirm benefit decision", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Benefit selection errors", exact: true }),
  ).toBeFocused();
  await page
    .getByRole("textbox", { name: /^Benefit selection reason/ })
    .fill("Synthetic BG independent HR approval");
  await page
    .getByRole("button", { name: "Confirm benefit decision", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(item).toHaveCount(0);
  await panel
    .getByRole("combobox", { name: /^Benefit queue status/ })
    .selectOption("APPROVED");
  await expect(item).toContainText("PENDING_PAYROLL");
  await item
    .getByText("Benefit request context and history", { exact: true })
    .click();
  await expect(item).toContainText("Synthetic BG independent HR approval");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await login(page, "sophia");
  panel = await queue(page);
  await panel
    .getByRole("combobox", { name: /^Benefit queue status/ })
    .selectOption("APPROVED");
  await expect(
    panel.locator("article").filter({ hasText: "BG-Q13" }),
  ).toContainText("PENDING_PAYROLL");
  await panel
    .getByRole("combobox", { name: /^Benefit queue status/ })
    .selectOption("PENDING");
  await panel
    .getByRole("combobox", { name: /^Benefit queue rows per page/ })
    .selectOption("32");
  const long = panel.locator("article").filter({ hasText: "BG-Q00" });
  await long
    .getByText("Benefit request context and history", { exact: true })
    .click();
  await expect(long).toContainText("Synthetic Family Member");
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
  await panel
    .getByRole("heading", { name: "Benefit selection requests", exact: true })
    .scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/benefit-selection-inbox-mobile.png",
  });
  await long.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/benefit-selection-inbox-context-mobile.png",
  });
});

test("benefit inbox API applies private scopes before totals and validates contracts and query limits", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    other = await identity("outsider");
  try {
    const first = (await checkResponse(
      await hr.client.get(path + "?scope=REVIEW&status=ALL&size=8"),
      path,
    )) as SelectionPage;
    expect(first.total).toBeGreaterThan(12);
    const rows = [...first.items];
    for (let p = 1; rows.length < first.total; p++) {
      const result = (await checkResponse(
        await hr.client.get(`${path}?scope=REVIEW&status=ALL&size=8&page=${p}`),
        path,
      )) as SelectionPage;
      expect(result.total).toBe(first.total);
      expect(result.items.length).toBeGreaterThan(0);
      rows.push(...result.items);
    }
    expect(new Set(rows.map((r) => r.id)).size).toBe(first.total);
    expect(
      rows.every((r) => r.employeeId !== "E001" && r.requestedBy !== "amelia"),
    ).toBe(true);
    const own = (await checkResponse(
      await self.client.get(path + "?scope=OWN&status=ALL&size=50"),
      path,
    )) as SelectionPage;
    expect(own.items.every((r) => r.employeeId === "E003")).toBe(true);
    for (const actor of [self, manager, other])
      expect(
        (
          await actor.client.get(path + "?scope=REVIEW", {
            headers: { "X-Role": "HR", "X-Tenant-ID": "atlas" },
          })
        ).status(),
      ).toBe(403);
    expect(
      (await other.client.get(path + "?scope=OWN&status=ALL")).status(),
    ).toBe(200);
    expect(
      (
        (await (
          await other.client.get(path + "?scope=OWN&status=ALL")
        ).json()) as SelectionPage
      ).total,
    ).toBe(0);
    for (const query of [
      "scope=BAD",
      "status=BAD",
      "page=-1",
      "page=100001",
      "size=0",
      "size=51",
    ])
      expect((await hr.client.get(path + "?" + query)).status()).toBe(400);
    const validate = schemaValidator("BenefitSelectionInboxPage");
    expect(validate(first)).toBe(true);
    expect(validate({ ...first, size: 51 })).toBe(false);
    expect(validate({ ...first, privateEmployee: "forbidden" })).toBe(false);
  } finally {
    await Promise.all(
      [hr, self, manager, other].map((a) => a.client.dispose()),
    );
  }
});

test("benefit queue fails independently, cancels a closed read and resets state on category navigation", async ({
  page,
}) => {
  await login(page, "amelia");
  await page.route(
    "**/api/v1/benefits/selection-inbox?*",
    (route) => route.fulfill({ status: 503 }),
    { times: 1 },
  );
  let panel = await queue(page);
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByRole("button", { name: "Retry benefit queue", exact: true })
    .click();
  await expect(
    panel.getByRole("heading", {
      name: "Benefit selection requests",
      exact: true,
    }),
  ).toBeFocused();
  let release!: () => void, entered!: () => void, finished!: () => void;
  const held = new Promise<void>((r) => {
      release = r;
    }),
    started = new Promise<void>((r) => {
      entered = r;
    }),
    done = new Promise<void>((r) => {
      finished = r;
    });
  await page.route(
    "**/api/v1/benefits/selection-inbox?*",
    async (route) => {
      entered();
      await held;
      await route.fulfill({ status: 503 }).catch(() => {});
      finished();
    },
    { times: 1 },
  );
  try {
    await panel
      .getByRole("button", { name: "Refresh benefit queue", exact: true })
      .click();
    await started;
    await expect(
      panel.getByText("Loading benefit queue…", { exact: true }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Employee changes", exact: true })
      .click();
    await expect(panel).toHaveCount(0);
    await page
      .getByRole("button", { name: "Benefit selections", exact: true })
      .click();
    panel = page.getByRole("region", {
      name: "Benefit selection approval queue",
      exact: true,
    });
    await expect(panel.locator("article").first()).toBeVisible();
    release();
    await done;
    await expect(panel.getByRole("alert")).toHaveCount(0);
    await panel
      .getByRole("combobox", { name: /^Benefit queue scope/ })
      .selectOption("OWN");
    await panel
      .getByRole("combobox", { name: /^Benefit queue status/ })
      .selectOption("WITHDRAWN");
    await expect(
      panel.getByText("No benefit requests match.", { exact: false }),
    ).toBeVisible();
    await page
      .getByRole("button", { name: "Employee changes", exact: true })
      .click();
    await page
      .getByRole("button", { name: "Benefit selections", exact: true })
      .click();
    await expect(
      panel.getByRole("combobox", { name: /^Benefit queue scope/ }),
    ).toHaveValue("REVIEW");
    await expect(
      panel.getByRole("combobox", { name: /^Benefit queue status/ }),
    ).toHaveValue("PENDING");
  } finally {
    release();
  }
});
