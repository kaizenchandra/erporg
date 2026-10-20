import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse, schemaValidator } from "./support/contract";
import type {
  ParentContext,
  ParentChange,
} from "../features/people/department-parent";
const contextPath = "/api/v1/organisation/departments/{code}/hierarchy",
  commandPath = "/api/v1/organisation/departments/{code}/parent-changes";
async function identity(user: string) {
  const client = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  let csrf = (await (await client.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  expect(
    (
      await client.post("/api/login", {
        headers: { [csrf.headerName]: csrf.token },
        form: { username: user, password: "atlas-demo" },
      })
    ).status(),
  ).toBe(204);
  csrf = (await (await client.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
  return { client, headers: { [csrf.headerName]: csrf.token } };
}
async function fixture(history = 0) {
  const hr = await identity("amelia"),
    prefix = `BJ-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
  const date = new Date().toISOString().slice(0, 10),
    a = `${prefix}-A`,
    b = `${prefix}-B`,
    child = `${prefix}-C`;
  try {
    for (const code of [a, b, child])
      expect(
        (
          await hr.client.post("/api/v1/organisation/departments", {
            headers: hr.headers,
            data: {
              code,
              name: code,
              parentCode: code === child ? a : null,
              costCentre: "DEMO-BJ",
              effectiveFrom: date,
              reason: "Synthetic hierarchy browser fixture",
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(200);
    for (let version = 0; version < history; version++)
      expect(
        (
          await hr.client.post(commandPath.replace("{code}", child), {
            headers: hr.headers,
            data: {
              version,
              parentCode: version % 2 === 0 ? b : a,
              effectiveDate: date,
              reason: `Synthetic correction history ${version}`,
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(200);
    return { a, b, child, date };
  } finally {
    await hr.client.dispose();
  }
}
async function open(page: Page, code: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await expect(page.getByLabel("Workspace account")).toBeHidden();
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  const directory = page.getByRole("region", {
    name: "Department directory",
    exact: true,
  });
  await directory.getByLabel("Search departments", { exact: true }).fill(code);
  await directory
    .getByRole("button", { name: `Manage parent for ${code}`, exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await expect(
    dialog.getByRole("button", { name: "Save parent correction", exact: true }),
  ).toBeVisible();
  return { dialog, directory };
}
async function chooseParent(page: Page, parent: string) {
  const dialog = page.getByRole("dialog");
  if (parent)
    await dialog.getByLabel("Find a department", { exact: true }).fill(parent);
  await dialog
    .getByRole("combobox", { name: /^New parent department/ })
    .selectOption(parent);
}
const confirmName = /^Confirm one department/;

test("HR corrects a current parent and root with confirmed impact, preserved validation and uncertain-response replay", async ({
  page,
}) => {
  const { a, b, child, date } = await fixture();
  const { directory } = await open(page, child);
  let dialog = page.getByRole("dialog");
  await expect(dialog).toContainText(`Current parent: ${a}`);
  await expect(dialog).toContainText(`Effective date: ${date} (UTC)`);
  await chooseParent(page, b);
  await dialog
    .getByRole("button", { name: "Save parent correction", exact: true })
    .click();
  await expect(
    dialog.getByRole("alert", {
      name: "Department parent errors",
      exact: true,
    }),
  ).toBeFocused();
  await dialog.getByRole("checkbox", { name: confirmName }).check();
  await dialog
    .getByRole("button", { name: "Save parent correction", exact: true })
    .click();
  await expect(
    dialog.getByRole("alert", {
      name: "Department parent errors",
      exact: true,
    }),
  ).toContainText("reason");
  await dialog
    .getByRole("textbox", { name: /^Parent correction reason/ })
    .fill("Synthetic HR reference hierarchy correction");
  await dialog.getByRole("checkbox", { name: confirmName }).check();
  await dialog
    .getByRole("button", { name: "Save parent correction", exact: true })
    .click();
  await expect(dialog).toHaveCount(0);
  await expect(
    directory.getByRole("heading", { name: "Browse departments", exact: true }),
  ).toBeFocused();
  await directory.getByLabel("Search departments", { exact: true }).fill(child);
  await expect(directory.locator("article")).toContainText(`Parent: ${b}`);
  await directory
    .getByRole("button", { name: `Manage parent for ${child}`, exact: true })
    .click();
  dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Loaded revision 1");
  await expect(dialog.locator(".history-event")).toContainText(
    "Synthetic HR reference hierarchy correction",
  );
  await chooseParent(page, "");
  await dialog
    .getByRole("textbox", { name: /^Parent correction reason/ })
    .fill("Synthetic root correction with interrupted response");
  await dialog.getByRole("checkbox", { name: confirmName }).check();
  const endpoint = commandPath.replace("{code}", child);
  let committed = false;
  await page.route(
    `**${endpoint}`,
    async (route) => {
      const saved = await route.fetch();
      expect(saved.status()).toBe(200);
      committed = true;
      await route.abort("failed");
    },
    { times: 1 },
  );
  await dialog
    .getByRole("button", { name: "Save parent correction", exact: true })
    .click();
  await expect(
    dialog.getByRole("alert", {
      name: "Department parent errors",
      exact: true,
    }),
  ).toBeFocused();
  expect(committed).toBe(true);
  await expect(
    dialog.getByRole("textbox", { name: /^Parent correction reason/ }),
  ).toHaveValue("Synthetic root correction with interrupted response");
  await dialog
    .getByRole("button", { name: "Save parent correction", exact: true })
    .click();
  await expect(dialog).toHaveCount(0);
  await directory.getByLabel("Search departments", { exact: true }).fill(child);
  await expect(directory.locator("article")).toContainText(
    "Parent: Root department",
  );
  await directory
    .getByRole("button", { name: `Manage parent for ${child}`, exact: true })
    .click();
  dialog = page.getByRole("dialog");
  await expect(dialog).toContainText("Loaded revision 2");
  await expect(dialog.locator(".history-event")).toHaveCount(2);
  await dialog
    .getByRole("textbox", { name: /^Parent correction reason/ })
    .fill("Unsaved retained draft");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(dialog).toBeVisible();
  page.once("dialog", (d) => d.accept());
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await expect(
    directory.getByRole("button", {
      name: `Manage parent for ${child}`,
      exact: true,
    }),
  ).toBeFocused();
});

test("parent correction API enforces current HR scope, exact dates and revisions, stable replay and single-winner concurrency", async () => {
  const { a, b, child, date } = await fixture();
  const hr = await identity("amelia"),
    second = await identity("olivia"),
    self = await identity("sophia"),
    other = await identity("outsider");
  const context = contextPath.replace("{code}", child),
    command = commandPath.replace("{code}", child);
  const data = {
    version: 0,
    parentCode: b,
    effectiveDate: date,
    reason: "Synthetic guarded API parent correction",
    idempotencyKey: crypto.randomUUID(),
  };
  try {
    const loaded = (await checkResponse(
      await hr.client.get(context),
      contextPath,
    )) as ParentContext;
    expect(loaded.department).toMatchObject({ parentCode: a, version: 0 });
    for (const actor of [self, other]) {
      expect(
        (await actor.client.get(`${context}?tenant=atlas&role=HR`)).status(),
      ).toBe(403);
      expect(
        (
          await actor.client.post(command, { headers: actor.headers, data })
        ).status(),
      ).toBe(403);
    }
    for (const version of [null, -1, 0.5, 9223372036854776000]) {
      const invalid = await hr.client.post(command, {
        headers: hr.headers,
        data: { ...data, version, idempotencyKey: crypto.randomUUID() },
      });
      expect(invalid.status()).toBe(400);
      await checkResponse(invalid, commandPath, "post");
    }
    expect(
      (
        await hr.client.post(command, {
          headers: hr.headers,
          data: { ...data, effectiveDate: null },
        })
      ).status(),
    ).toBe(400);
    expect(
      (
        await hr.client.post(command, {
          headers: hr.headers,
          data: { ...data, effectiveDate: "2099-01-01" },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await hr.client.post(command, {
          headers: hr.headers,
          data: { ...data, parentCode: child },
        })
      ).status(),
    ).toBe(400);
    expect(
      (
        await hr.client.post(command, {
          headers: hr.headers,
          data: { ...data, parentCode: a },
        })
      ).status(),
    ).toBe(400);
    const saved = (await checkResponse(
      await hr.client.post(command, { headers: hr.headers, data }),
      commandPath,
      "post",
    )) as ParentChange;
    expect(saved).toMatchObject({
      version: 1,
      oldParentCode: a,
      parentCode: b,
      effectiveDate: date,
      actor: "amelia",
    });
    expect(
      await checkResponse(
        await hr.client.post(command, { headers: hr.headers, data }),
        commandPath,
        "post",
      ),
    ).toEqual(saved);
    expect(
      (
        await hr.client.post(command, {
          headers: hr.headers,
          data: { ...data, parentCode: null },
        })
      ).status(),
    ).toBe(409);
    const cycle = await hr.client.post(commandPath.replace("{code}", b), {
      headers: hr.headers,
      data: { ...data, parentCode: child, idempotencyKey: crypto.randomUUID() },
    });
    expect(cycle.status()).toBe(400);
    const race = await Promise.all([
      hr.client.post(command, {
        headers: hr.headers,
        data: {
          ...data,
          version: 1,
          parentCode: null,
          idempotencyKey: crypto.randomUUID(),
        },
      }),
      second.client.post(command, {
        headers: second.headers,
        data: {
          ...data,
          version: 1,
          parentCode: a,
          idempotencyKey: crypto.randomUUID(),
        },
      }),
    ]);
    expect(race.map((r) => r.status()).sort()).toEqual([200, 409]);
    const first = (await checkResponse(
      await hr.client.get(`${context}?size=1`),
      contextPath,
    )) as ParentContext;
    const last = (await checkResponse(
      await hr.client.get(`${context}?size=1&page=1`),
      contextPath,
    )) as ParentContext;
    expect(first).toMatchObject({ total: 2, page: 0, size: 1 });
    expect(first.items[0].version).toBe(2);
    expect(last.items[0].version).toBe(1);
    for (const q of ["page=-1", "page=100001", "size=0", "size=51"]) {
      const invalid = await hr.client.get(`${context}?${q}`);
      expect(invalid.status()).toBe(400);
      await checkResponse(invalid, contextPath);
    }
    const validate = schemaValidator("DepartmentParentContext");
    expect(
      validate({ ...first, items: [{ ...first.items[0], version: 0 }] }),
    ).toBe(false);
    expect(validate({ ...first, size: 51 })).toBe(false);
    const directory = (await (
      await self.client.get(
        `/api/v1/organisation/department-directory?search=${child}`,
      )
    ).json()) as { items: { parentCode: string | null }[] };
    expect(directory.items[0].parentCode).toBe(first.department.parentCode);
    const legacy = (await (
      await self.client.get("/api/v1/organisation/departments")
    ).json()) as { code: string; parentCode: string | null }[];
    expect(legacy.find((d) => d.code === child)?.parentCode).toBe(
      first.department.parentCode,
    );
  } finally {
    await Promise.all([
      hr.client.dispose(),
      second.client.dispose(),
      self.client.dispose(),
      other.client.dispose(),
    ]);
  }
});

test("paged hierarchy history preserves a draft during retry and cancels closed reads on mobile", async ({
  page,
}) => {
  const { child } = await fixture(11);
  const { dialog, directory } = await open(page, child);
  const reason = dialog.getByRole("textbox", {
    name: /^Parent correction reason/,
  });
  await reason.fill("Synthetic draft survives history retry");
  const history = dialog.getByRole("region", {
    name: "Department parent correction history",
    exact: true,
  });
  await expect(history.locator(".history-event")).toHaveCount(10);
  const path = contextPath.replace("{code}", child);
  await page.route(
    `**${path}?*`,
    (r) =>
      r.fulfill({
        status: 503,
        contentType: "application/json",
        body: JSON.stringify({
          status: 503,
          title: "Synthetic hierarchy read outage",
        }),
      }),
    { times: 1 },
  );
  await history
    .getByRole("button", { name: "Next parent corrections", exact: true })
    .click();
  await expect(history.getByRole("alert")).toBeFocused();
  await expect(reason).toHaveValue("Synthetic draft survives history retry");
  await history
    .getByRole("button", { name: "Retry hierarchy history", exact: true })
    .click();
  await expect(
    history.getByRole("heading", {
      name: "Parent correction history",
      exact: true,
    }),
  ).toBeFocused();
  await expect(history.locator(".history-event")).toHaveCount(1);
  await expect(reason).toHaveValue("Synthetic draft survives history retry");
  await page.setViewportSize({ width: 390, height: 844 });
  await reason.scrollIntoViewIfNeeded();
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
  await page.screenshot({ path: "docs/evidence/department-parent-mobile.png" });
  await history
    .getByRole("heading", { name: "Parent correction history", exact: true })
    .scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/department-parent-history-mobile.png",
  });
  let release: () => void = () => {},
    started: () => void = () => {};
  const held = new Promise<void>((r) => {
      release = r;
    }),
    waiting = new Promise<void>((r) => {
      started = r;
    });
  await page.route(
    `**${path}?*`,
    async (r) => {
      started();
      await held;
      await r
        .fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({
            status: 503,
            title: "Obsolete closed hierarchy",
          }),
        })
        .catch(() => {});
    },
    { times: 1 },
  );
  await history
    .getByRole("button", { name: "Previous parent corrections", exact: true })
    .click();
  await waiting;
  page.once("dialog", (d) => d.accept());
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  release();
  await expect(
    directory.getByRole("button", {
      name: `Manage parent for ${child}`,
      exact: true,
    }),
  ).toBeFocused();
  await expect(page.getByText("Obsolete closed hierarchy")).toHaveCount(0);
});
