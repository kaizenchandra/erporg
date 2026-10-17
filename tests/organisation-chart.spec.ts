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
  return c;
}
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
}
const chart = (page: Page) =>
  page.getByRole("region", { name: "Organisation chart", exact: true });
test("HR browses independent hierarchy, searches beyond directory filter and preserves branch around profiles", async ({
  page,
}) => {
  await login(page, "amelia");
  await page.getByRole("textbox", { name: "Search employees" }).fill("Amelia");
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const region = chart(page);
  await expect(region.getByRole("article")).toHaveCount(1);
  await region
    .getByRole("button", {
      name: "Browse reports of Amelia Bennett",
      exact: true,
    })
    .click();
  await expect(
    region.getByRole("article", {
      name: "Organisation member E002",
      exact: true,
    }),
  ).toBeVisible();
  await region
    .getByRole("button", { name: "Browse reports of Marcus Chen", exact: true })
    .click();
  await expect(region.locator(".organisation-parent")).toContainText(
    "Marcus Chen",
  );
  await region
    .getByRole("button", {
      name: "View profile for Sophia Martinez",
      exact: true,
    })
    .click();
  await expect(page.getByRole("dialog")).toContainText(
    "sophia.martinez@example.test",
  );
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(region.locator(".organisation-parent")).toContainText(
    "Marcus Chen",
  );
  await region.getByRole("button", { name: "Back to previous branch" }).click();
  await expect(region.locator(".organisation-parent")).toContainText(
    "Amelia Bennett",
  );
  await region.getByLabel("Search organisation members").fill("Lucas");
  await region
    .getByRole("button", { name: "Search chart", exact: true })
    .click();
  await expect(region.getByRole("article")).toHaveCount(1);
  await expect(region).toContainText("Lucas Garcia");
  await region.getByRole("button", { name: "Organisation roots" }).click();
  await region
    .getByRole("button", {
      name: "Browse reports of Amelia Bennett",
      exact: true,
    })
    .click();
  await region
    .getByRole("button", { name: "Browse reports of Marcus Chen", exact: true })
    .click();
  await region
    .getByRole("heading", { name: "Your organisational view" })
    .focus();
  await region.scrollIntoViewIfNeeded();
  await region.screenshot({
    path: "docs/evidence/organisation-chart.png",
  });
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
});
test("manager and employee charts retain record scope, empty states and usable mobile layout", async ({
  page,
}) => {
  await login(page, "marcus");
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const region = chart(page);
  await expect(region.getByRole("article")).toHaveCount(1);
  await region
    .getByRole("button", { name: "Browse reports of Marcus Chen" })
    .click();
  await expect(region).toContainText("Manager is outside your authorised view");
  await expect(region).not.toContainText("E001");
  await expect(
    region.getByRole("button", { name: /Browse visible manager/ }),
  ).toHaveCount(0);
  await region.getByLabel("Search organisation members").fill("Amelia");
  await region
    .getByRole("button", { name: "Search chart", exact: true })
    .click();
  await expect(region).toContainText("No visible members");
  await region.getByRole("button", { name: "Organisation roots" }).click();
  await region
    .getByRole("button", { name: "Browse reports of Marcus Chen" })
    .click();
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
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
  await login(page, "sophia");
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  await expect(region.getByRole("article")).toHaveCount(1);
  await expect(region).toContainText("Sophia Martinez");
  await expect(
    region.getByRole("button", { name: "Browse reports of Sophia Martinez" }),
  ).toBeDisabled();
});
test("chart API enforces scope, minimal fields, literal search and complete stable pagination", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const path = "/api/v1/organisation/chart";
    const root = await hr.get(path);
    await checkResponse(root, path);
    const branch = await manager.get(path + "?parentId=E002");
    await checkResponse(branch, path);
    const visible = await branch.json();
    expect(visible.manager).toBeNull();
    expect(visible.managerOutsideScope).toBe(true);
    expect(JSON.stringify(visible)).not.toContain("E001");
    for (const c of [self, manager, outside])
      expect(
        (
          await c.get(path + "?parentId=E001", {
            headers: { "X-Tenant": "atlas", "X-Role": "HR" },
          })
        ).status(),
      ).toBe(403);
    expect((await outside.get(path + "?parentId=E003")).status()).toBe(403);
    expect((await (await self.get(path + "?search=E002")).json()).total).toBe(
      0,
    );
    expect(
      (await (await outside.get(path)).json()).items.map(
        (n: { id: string }) => n.id,
      ),
    ).toEqual(["E900"]);
    for (const query of [
      "parentId=E002&search=test",
      "size=51",
      "page=-1",
      "parentId=bad%2Fid",
    ])
      expect((await hr.get(path + "?" + query)).status()).toBe(400);
    const all = await (await hr.get(path + "?parentId=E002&size=50")).json();
    const ids: string[] = [];
    for (let page = 0; page < Math.ceil(all.total / 2); page++) {
      const r = await hr.get(`${path}?parentId=E002&size=2&page=${page}`);
      await checkResponse(r, path);
      ids.push(...(await r.json()).items.map((n: { id: string }) => n.id));
    }
    expect(ids).toEqual(all.items.map((n: { id: string }) => n.id));
    expect(new Set(ids).size).toBe(all.total);
    expect((await (await hr.get(path + "?search=%25%27")).json()).total).toBe(
      0,
    );
    const forged = await (await self.get(path + "?tenant=other")).json();
    expect(forged.items.map((n: { id: string }) => n.id)).toEqual(["E003"]);
  } finally {
    await Promise.all([
      hr.dispose(),
      self.dispose(),
      manager.dispose(),
      outside.dispose(),
    ]);
  }
});
test("navigating away cancels an in-flight real profile response", async ({
  page,
}) => {
  await login(page, "amelia");
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const region = chart(page);
  await region
    .getByRole("button", {
      name: "Browse reports of Amelia Bennett",
      exact: true,
    })
    .click();
  await region
    .getByRole("button", { name: "Browse reports of Marcus Chen", exact: true })
    .click();
  let release!: () => void, observed!: () => void;
  const gate = new Promise<void>((r) => (release = r)),
    started = new Promise<void>((r) => (observed = r));
  await page.route("**/api/v1/employees/E003", async (route) => {
    const response = await route.fetch();
    observed();
    await gate;
    await route.fulfill({ response }).catch(() => {});
  });
  try {
    await region
      .getByRole("button", {
        name: "View profile for Sophia Martinez",
        exact: true,
      })
      .click();
    await started;
    const cancelled = page.waitForEvent("requestfailed", (r) =>
      r.url().endsWith("/api/v1/employees/E003"),
    );
    await region
      .getByRole("button", { name: "Back to previous branch" })
      .click();
    await cancelled;
    release();
    await expect(region.locator(".organisation-parent")).toContainText(
      "Amelia Bennett",
    );
    await expect(page.getByRole("dialog")).toHaveCount(0);
  } finally {
    release();
    await page.unroute("**/api/v1/employees/E003");
  }
});
