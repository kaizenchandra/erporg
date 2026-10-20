import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse, schemaValidator } from "./support/contract";
import type { DepartmentPage } from "../features/people/department-directory";
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
test("department directory resolves unseen parents, pages reference branches and recovers stale/mobile reads", async ({
  page,
}) => {
  await login(page);
  const token = (await (await page.request.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  const date = new Date().toISOString().slice(0, 10);
  const future = new Date(Date.now() + 86400000).toISOString().slice(0, 10);
  for (let i = 0; i < 12; i++) {
    const response = await page.request.post(
      "/api/v1/organisation/departments",
      {
        headers: { [token.headerName]: token.token },
        data: {
          code: `BA-${String(i).padStart(2, "0")}`,
          name:
            i === 0
              ? "ZZZ BA directory parent"
              : `BA directory child ${String(i).padStart(2, "0")}`,
          parentCode: i === 0 ? null : "BA-00",
          costCentre: "DEMO-BA",
          effectiveFrom: i === 11 ? future : date,
          reason: "Synthetic directory acceptance",
          idempotencyKey: crypto.randomUUID(),
        },
      },
    );
    expect(response.status()).toBe(200);
  }
  await page.route(
    "**/api/v1/organisation/department-directory?*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          title: "Directory temporarily unavailable",
          status: 503,
        }),
      }),
    { times: 1 },
  );
  await page.route(
    "**/api/v1/organisation/reference-history?kind=DEPARTMENT&*",
    (route) =>
      route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({ status: 503, title: "History unavailable" }),
      }),
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const directory = page.getByRole("region", {
    name: "Department directory",
    exact: true,
  });
  await expect(directory.getByRole("alert")).toBeFocused();
  await directory
    .getByRole("button", { name: "Retry department directory", exact: true })
    .click();
  await expect(
    directory.getByRole("heading", { name: "Browse departments", exact: true }),
  ).toBeFocused();
  await expect(directory.locator("article")).toHaveCount(8);
  await directory
    .getByRole("button", { name: "Next departments", exact: true })
    .click();
  await expect(directory.getByRole("status")).toContainText("Page 2");
  await directory
    .getByLabel("Search departments", { exact: true })
    .fill("BA-01");
  await expect(directory.getByRole("status")).toContainText(
    /^1 department · Page 1 ·/,
  );
  await expect(directory).toContainText(
    "Parent: ZZZ BA directory parent (BA-00)",
  );
  await page
    .getByText("Department configuration history", { exact: true })
    .click();
  await page
    .getByRole("button", { name: "Retry department history", exact: true })
    .click();
  await expect(
    directory.getByLabel("Search departments", { exact: true }),
  ).toHaveValue("BA-01");
  await expect(directory.locator("article h4")).toHaveText(
    "BA directory child 01",
  );
  await directory
    .getByLabel("Search departments", { exact: true })
    .fill("BA-00");
  await directory
    .getByRole("button", {
      name: "View direct departments of ZZZ BA directory parent",
      exact: true,
    })
    .click();
  await expect(directory.getByRole("status")).toContainText(
    /^11 departments · Page 1 ·/,
  );
  await directory
    .getByRole("combobox", { name: "Department timing", exact: true })
    .selectOption("PLANNED");
  await expect(directory).toContainText("BA directory child 11");
  await expect(directory.getByRole("status")).toContainText(/^1 department ·/);
  await directory
    .getByRole("combobox", { name: "Department relationship", exact: true })
    .selectOption("ROOTS");
  await directory
    .getByLabel("Search departments", { exact: true })
    .fill("BA directory");
  await expect(directory).toContainText("No matching departments.");
  await directory
    .getByRole("combobox", { name: "Department timing", exact: true })
    .selectOption("ALL");
  await expect(directory.getByRole("status")).toContainText(/^1 department ·/);
  await directory
    .getByRole("combobox", { name: "Department relationship", exact: true })
    .selectOption("ALL");
  await directory
    .getByRole("combobox", { name: "Department name order", exact: true })
    .selectOption("desc");
  await directory
    .getByRole("combobox", { name: "Departments per page", exact: true })
    .selectOption("20");
  await expect(directory.locator("article")).toHaveCount(12);
  expect(await directory.locator("article h4").first().textContent()).toBe(
    "ZZZ BA directory parent",
  );
  let release = () => {};
  const hold = new Promise<void>((r) => {
    release = r;
  });
  let ready = () => {};
  const received = new Promise<void>((r) => {
    ready = r;
  });
  let finish = () => {};
  const done = new Promise<void>((r) => {
    finish = r;
  });
  await page.route(
    "**/api/v1/organisation/department-directory?search=BA-01&*",
    async (route) => {
      const response = await route.fetch();
      ready();
      await hold;
      await route.fulfill({ response });
      finish();
    },
    { times: 1 },
  );
  await directory
    .getByLabel("Search departments", { exact: true })
    .fill("BA-01");
  await received;
  await directory
    .getByLabel("Search departments", { exact: true })
    .fill("BA-02");
  await expect(directory).toContainText("BA directory child 02");
  release();
  await done;
  await expect(directory.locator("article h4")).toHaveText(
    "BA directory child 02",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await directory
    .getByRole("heading", { name: "Browse departments", exact: true })
    .scrollIntoViewIfNeeded();
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
  await directory.screenshot({
    path: "docs/evidence/department-directory-mobile.png",
  });
  await page
    .getByRole("button", { name: "Create department", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog
    .getByLabel("Search parent departments", { exact: true })
    .fill("BA-00");
  await dialog
    .getByRole("combobox", { name: "Parent department", exact: true })
    .selectOption("BA-00");
  await dialog
    .getByLabel("Search parent departments", { exact: true })
    .fill("no matching parent");
  await expect(
    dialog.getByRole("combobox", { name: "Parent department", exact: true }),
  ).toHaveValue("BA-00");
  await dialog.getByLabel("Department code").fill("BA-FORM");
  await dialog.getByLabel("Department name").fill("BA form child");
  await dialog.getByLabel("Cost-centre code").fill("DEMO-BA");
  await dialog
    .getByLabel("Configuration reason")
    .fill("Synthetic searched parent acceptance");
  await dialog
    .getByRole("button", { name: "Create department", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeHidden();
  await expect(
    page.getByRole("region", { name: "Department directory", exact: true }),
  ).toHaveCount(1);
  await expect(
    page.getByText("Department configuration history", { exact: true }),
  ).toHaveCount(1);
  await expect(
    directory.getByLabel("Search departments", { exact: true }),
  ).toHaveValue("BA-FORM");
  await expect(directory).toContainText(
    "Parent: ZZZ BA directory parent (BA-00)",
  );
});
test("department directory contracts enforce bounds, tenant identity and literal filtering", async ({
  page,
}) => {
  await login(page, "sophia");
  const path = "/api/v1/organisation/department-directory";
  const result = (await checkResponse(
    await page.request.get(path),
    path,
  )) as DepartmentPage;
  expect(result.items.length).toBeLessThanOrEqual(8);
  for (const query of [
    "size=51",
    "page=-1",
    "size=1.5",
    "timing=UNKNOWN",
    "direction=unsafe",
    "relationship=ROOTS&parentCode=BA-00",
    "parentCode=bad",
    "search=" + "x".repeat(121),
  ])
    expect((await page.request.get(path + "?" + query)).status()).toBe(400);
  const literal = (await (
    await page.request.get(path + "?search=" + encodeURIComponent("%'_"))
  ).json()) as DepartmentPage;
  expect(literal.total).toBe(0);
  const outsider = await request.newContext({
    baseURL: "http://127.0.0.1:3000",
  });
  try {
    const csrf = (await (await outsider.get("/api/csrf")).json()) as {
      headerName: string;
      token: string;
    };
    expect(
      (
        await outsider.post("/api/login", {
          form: { username: "outsider", password: "atlas-demo" },
          headers: { [csrf.headerName]: csrf.token },
        })
      ).status(),
    ).toBe(204);
    const body = (await (
      await outsider.get(path + "?search=BA-&tenant=atlas", {
        headers: { "X-Tenant-ID": "atlas", "X-Role": "HR" },
      })
    ).json()) as DepartmentPage;
    expect(body.total).toBe(0);
  } finally {
    await outsider.dispose();
  }
  const validate = schemaValidator("DepartmentDirectoryPage");
  expect(validate(result)).toBe(true);
  expect(validate({ ...result, privateEmployee: "forbidden" })).toBe(false);
  expect(validate({ ...result, size: 51 })).toBe(false);
  expect(
    validate({
      ...result,
      items: [...result.items, ...Array(51).fill(result.items[0])],
    }),
  ).toBe(false);
});
