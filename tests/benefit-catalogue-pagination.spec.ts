import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
type PlanPage = {
  items: { code: string; eligible: boolean; monthlyContribution: string }[];
  total: number;
  page: number;
  size: number;
  evaluatedOn: string;
};
async function login(page: Page, user = "amelia") {
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
const path = "/api/v1/benefits/plans";
test("catalogue exceeds1000 with exact bounded totals, literal filters and isolated tenant reads", async ({
  page,
}) => {
  test.setTimeout(120000);
  await login(page);
  const csrf = (await (await page.request.get("/api/csrf")).json()) as {
    token: string;
    headerName: string;
  };
  for (let i = 0; i < 1005; i++) {
    const response = await page.request.post(path, {
      headers: { [csrf.headerName]: csrf.token },
      data: {
        code: `BF-C${String(i).padStart(4, "0")}`,
        name: `Synthetic BF benefit ${String(i).padStart(4, "0")}`,
        currency: "GBP",
        monthlyContribution: "12.50",
        minimumServiceDays: "0",
        effectiveFrom: new Date().toISOString().slice(0, 10),
        reason: "Synthetic complete benefit catalogue fixture",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(response.status()).toBe(200);
    if (i % 100 === 0) await checkResponse(response, path, "post");
  }
  for (const eligibility of ["ALL", "ELIGIBLE"]) {
    const rows: PlanPage["items"] = [];
    for (let pageNo = 0; pageNo < 11; pageNo++) {
      const result = (await checkResponse(
        await page.request.get(
          `${path}?search=Synthetic%20BF%20benefit&eligibility=${eligibility}&size=100&page=${pageNo}`,
        ),
        path,
      )) as PlanPage;
      expect(result.total).toBe(1005);
      expect(result.items).toHaveLength(pageNo === 10 ? 5 : 100);
      expect(
        result.items.every(
          (p) => p.eligible && p.monthlyContribution === "12.50",
        ),
      ).toBe(true);
      rows.push(...result.items);
    }
    expect(new Set(rows.map((p) => p.code)).size).toBe(1005);
    expect(rows.at(-1)?.code).toBe("BF-C1004");
  }
  const end = (await checkResponse(
    await page.request.get(`${path}?search=BF-C1004`),
    path,
  )) as PlanPage;
  expect(end.total).toBe(1);
  for (const literal of ["%", "_", "\\"])
    expect(
      (
        (await (
          await page.request.get(
            `${path}?search=${encodeURIComponent(literal)}`,
          )
        ).json()) as PlanPage
      ).total,
    ).toBe(0);
  const other = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  try {
    const token = (await (await other.get("/api/csrf")).json()) as {
      token: string;
      headerName: string;
    };
    expect(
      (
        await other.post("/api/login", {
          headers: { [token.headerName]: token.token },
          form: { username: "outsider", password: "atlas-demo" },
        })
      ).status(),
    ).toBe(204);
    const result = (await checkResponse(
      await other.get(`${path}?search=BF-C1004`, {
        headers: { "X-Tenant-ID": "atlas", "X-Role": "HR" },
      }),
      path,
    )) as PlanPage;
    expect(result.total).toBe(0);
    expect(result.items).toEqual([]);
  } finally {
    await other.dispose();
  }
});

test("employee browses full catalogue, recovers retained filters and selects a plan beyond the former cap", async ({
  page,
}) => {
  await login(page, "sophia");
  await page.getByRole("button", { name: "Benefits", exact: true }).click();
  const catalogue = page.getByRole("region", {
    name: "Benefits catalogue",
    exact: true,
  });
  const results = catalogue.getByRole("status", {
    name: "Benefit plan results",
    exact: true,
  });
  await catalogue
    .getByLabel("Search benefit plans", { exact: true })
    .fill("Synthetic BF benefit");
  await expect(results).toContainText("1005 matching plans");
  await catalogue.getByLabel("Benefit plans per page").selectOption("16");
  await expect(catalogue.locator(".benefit-card")).toHaveCount(16);
  await expect(results).toContainText("Showing 1–16 of 1005");
  await catalogue
    .getByRole("button", { name: "Next benefit plans", exact: true })
    .focus();
  await page.keyboard.press("Enter");
  await expect(results).toBeFocused();
  await expect(results).toContainText("Page 2");
  await expect(results).toContainText("Showing 17–32 of 1005");
  await catalogue
    .getByRole("button", { name: "Refresh benefit plans", exact: true })
    .click();
  await expect(results).toBeFocused();
  await expect(results).toContainText("Showing 1–16 of 1005");
  await catalogue.getByLabel("Benefit name order").selectOption("desc");
  await expect(results).toContainText("Page 1");
  await expect(catalogue.locator(".benefit-card").first()).toContainText(
    "BF-C1004",
  );
  await catalogue
    .getByRole("combobox", { name: /^Benefit eligibility/ })
    .selectOption("ELIGIBLE");
  await expect(results).toContainText("1005 matching plans");
  await page.route(
    "**/api/v1/benefits/plans?*",
    (route) => route.fulfill({ status: 503 }),
    { times: 1 },
  );
  await catalogue
    .getByLabel("Search benefit plans", { exact: true })
    .fill("BF-C1004");
  await expect(catalogue.getByRole("alert")).toBeFocused();
  await expect(
    catalogue.getByLabel("Search benefit plans", { exact: true }),
  ).toHaveValue("BF-C1004");
  await catalogue
    .getByRole("button", { name: "Retry benefit plans", exact: true })
    .click();
  await expect(results).toBeFocused();
  await expect(catalogue.locator(".benefit-card")).toHaveCount(1);
  await catalogue
    .getByLabel("Search benefit plans", { exact: true })
    .fill("BF-MISSING");
  await expect(results).toContainText("Showing 0–0 of 0");
  await expect(catalogue).toContainText("No benefit plans match.");
  await catalogue
    .getByLabel("Search benefit plans", { exact: true })
    .fill("BF-C1004");
  await expect(catalogue.locator(".benefit-card")).toHaveCount(1);
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
  await catalogue.getByLabel("Benefit plans per page").scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/benefit-catalogue-pagination-mobile.png",
  });
  await catalogue
    .getByRole("button", {
      name: "Select Synthetic BF benefit 1004",
      exact: true,
    })
    .click();
  await page
    .getByRole("textbox", { name: /^Benefit selection reason/ })
    .fill("Synthetic selection beyond former catalogue cap");
  await page
    .getByRole("button", { name: "Submit benefit selection", exact: true })
    .click();
  const item = page
    .getByRole("region", { name: "Benefit selections", exact: true })
    .locator("article")
    .filter({ hasText: "Synthetic BF benefit 1004" });
  await expect(item).toContainText("PENDING");
  await item
    .getByText("Dependants and selection history", { exact: true })
    .click();
  await expect(item).toContainText(
    "Synthetic selection beyond former catalogue cap",
  );
  const own = (await checkResponse(
    await page.request.get("/api/v1/benefits/selections"),
    "/api/v1/benefits/selections",
  )) as {
    items: { planCode: string; status: string; monthlyContribution: string }[];
  };
  expect(
    own.items.find((selection) => selection.planCode === "BF-C1004"),
  ).toMatchObject({ status: "PENDING", monthlyContribution: "12.50" });
});
