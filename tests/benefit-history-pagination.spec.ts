import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse, schemaValidator } from "./support/contract";
import type { BenefitHistoryPage } from "../features/benefits/history";
const path = "/api/v1/benefits/creation-history";
async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
}
test("complete benefit journal traverses beyond100, retains original provenance and excludes higher IDs until refresh", async ({
  page,
}) => {
  test.setTimeout(60000);
  await login(page);
  const csrf = (await (await page.request.get("/api/csrf")).json()) as {
    token: string;
    headerName: string;
  };
  const headers = { [csrf.headerName]: csrf.token };
  const create = async (
    code: string,
    reason = "Synthetic complete benefit history",
  ) => {
    const response = await page.request.post("/api/v1/benefits/plans", {
      headers,
      data: {
        code,
        name: `Synthetic BE benefit ${code}`,
        currency: "GBP",
        monthlyContribution: "12.50",
        minimumServiceDays: "0",
        effectiveFrom: new Date().toISOString().slice(0, 10),
        reason,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(response.status()).toBe(200);
    await checkResponse(response, "/api/v1/benefits/plans", "post");
  };
  for (let i = 0; i < 105; i++)
    await create(
      `BE-H${String(i).padStart(3, "0")}`,
      i === 104 ? "Synthetic long reason " + "界".repeat(300) : undefined,
    );
  const first = (await checkResponse(
    await page.request.get(path + "?size=50"),
    path,
  )) as BenefitHistoryPage;
  expect(first.total).toBeGreaterThan(100);
  const rows = [...first.items];
  for (let pageNo = 1; rows.length < first.total; pageNo++) {
    const result = (await checkResponse(
      await page.request.get(
        `${path}?size=50&page=${pageNo}&throughId=${first.throughId}`,
      ),
      path,
    )) as BenefitHistoryPage;
    expect(result.total).toBe(first.total);
    expect(result.items.length).toBeGreaterThan(0);
    rows.push(...result.items);
  }
  expect(new Set(rows.map((r) => r.id)).size).toBe(first.total);
  expect(rows.filter((r) => r.code.startsWith("BE-H"))).toHaveLength(105);
  expect(rows.find((r) => r.code === "BE-H000")).toMatchObject({
    actor: "amelia",
    reason: "Synthetic complete benefit history",
  });
  const legacy = (await (
    await page.request.get("/api/v1/benefits/history")
  ).json()) as { code: string }[];
  expect(legacy).toHaveLength(100);
  expect(legacy.some((r) => r.code === "BE-H000")).toBe(false);
  const older = (await checkResponse(
    await page.request.get(path + "?code=BE-H000&order=asc"),
    path,
  )) as BenefitHistoryPage;
  expect(older.total).toBe(1);
  expect(older.items[0].code).toBe("BE-H000");
  await create("BE-LATER");
  const bounded = (await checkResponse(
    await page.request.get(`${path}?size=50&throughId=${first.throughId}`),
    path,
  )) as BenefitHistoryPage;
  expect(bounded.total).toBe(first.total);
  expect(
    ((await (await page.request.get(path)).json()) as BenefitHistoryPage).total,
  ).toBe(first.total + 1);
  for (const query of [
    "page=1",
    "size=51",
    "size=0",
    "code=lower",
    "order=unsafe",
    "throughId=01",
    "throughId=9223372036854775808",
    "page=100001",
  ])
    expect((await page.request.get(`${path}?${query}`)).status()).toBe(400);
  const validate = schemaValidator("BenefitCreationHistoryPage");
  expect(validate(first)).toBe(true);
  expect(validate({ ...first, throughId: 1 })).toBe(false);
  expect(validate({ ...first, items: [{ ...first.items[0], id: 1 }] })).toBe(
    false,
  );
  expect(validate({ ...first, privateEmployee: "forbidden" })).toBe(false);
  expect(validate({ ...first, size: 51 })).toBe(false);
  for (const user of ["sophia", "marcus", "outsider"]) {
    const client = await request.newContext({
      baseURL: "http://127.0.0.1:3000",
    });
    try {
      const token = (await (await client.get("/api/csrf")).json()) as {
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
      expect(
        (
          await client.get(`${path}?throughId=${first.throughId}`, {
            headers: { "X-Role": "HR", "X-Tenant-ID": "atlas" },
          })
        ).status(),
      ).toBe(403);
    } finally {
      await client.dispose();
    }
  }
});

test("HR browses older benefit events with exact filters, ordered pages and mobile recovery", async ({
  page,
}) => {
  await login(page);
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
  await page
    .getByText("Benefit configuration history", { exact: true })
    .click();
  const panel = page.getByRole("region", {
    name: "Benefit configuration events",
    exact: true,
  });
  await expect(panel.locator(".history-event")).toHaveCount(10);
  await panel
    .getByRole("button", { name: "Next benefit history", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(panel.getByRole("heading")).toBeFocused();
  await expect(panel.getByRole("status")).toContainText("Page 2");
  await panel.getByLabel("Benefit history rows per page").selectOption("50");
  await expect(panel.locator(".history-event")).toHaveCount(50);
  await expect(panel.getByRole("status")).toContainText("Page 1");
  await panel.getByLabel("Benefit history recording order").selectOption("asc");
  await expect(panel.getByRole("status")).toContainText("Oldest first");
  await panel.getByLabel("Benefit history reference code").fill("be-h000");
  await panel
    .getByRole("button", { name: "Apply benefit history code", exact: true })
    .click();
  await expect(panel.locator(".history-event")).toHaveCount(1);
  await expect(panel.locator(".history-event")).toContainText("BE-H000");
  await expect(
    panel.getByRole("button", { name: "Next benefit history", exact: true }),
  ).toBeDisabled();
  await panel.getByLabel("Benefit history reference code").fill("MISSING");
  await panel
    .getByRole("button", { name: "Apply benefit history code", exact: true })
    .click();
  await expect(panel).toContainText("No matching creation events.");
  await page.route(
    "**/api/v1/benefits/creation-history?*",
    (route) => route.fulfill({ status: 503 }),
    { times: 1 },
  );
  await panel
    .getByRole("button", { name: "Refresh benefit history", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await expect(
    page.getByRole("heading", { name: "Your benefits", exact: true }),
  ).toBeVisible();
  await panel
    .getByRole("button", { name: "Retry benefit history", exact: true })
    .click();
  await expect(panel.getByRole("heading")).toBeFocused();
  await expect(panel).toContainText("No matching creation events.");
  await panel.getByLabel("Benefit history reference code").fill("BE-H104");
  await panel
    .getByRole("button", { name: "Apply benefit history code", exact: true })
    .click();
  await expect(panel.locator(".history-event")).toHaveCount(1);
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
  await panel.getByRole("heading").scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/benefit-history-pagination-mobile.png",
  });
  await panel.locator(".history-event").scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/benefit-history-pagination-event-mobile.png",
  });
});
