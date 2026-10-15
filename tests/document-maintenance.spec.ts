import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
const today = () => new Date().toISOString().slice(0, 10);
async function fixture(user: string) {
  const hr = await identity("amelia"),
    self = await identity(user);
  const code = `MAINT-${Date.now()}`;
  expect(
    (
      await hr.c.post("/api/v1/document-templates", {
        headers: hr.headers,
        data: {
          code,
          title: "Original requirement",
          body: "Original synthetic instructions",
          required: true,
          effectiveDate: today(),
          reason: "Synthetic maintenance fixture",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(200);
  const files = [];
  for (const label of ["Original", "Corrected"]) {
    const filename = `${label}-${Date.now()}.txt`;
    const r = await self.c.post("/api/v1/documents", {
      headers: self.headers,
      data: {
        filename,
        mediaType: "text/plain",
        contentBase64: Buffer.from(`Synthetic ${label}`).toString("base64"),
        reason: "Synthetic correction fixture",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(r.status()).toBe(200);
    files.push(
      (await r.json()) as {
        id: string;
        filename: string;
        employeeId: string;
        version: number;
      },
    );
  }
  expect(
    (
      await self.c.post(`/api/v1/document-templates/${code}/evidence`, {
        headers: self.headers,
        data: {
          documentId: files[0].id,
          expectedVersion: 1,
          reason: "Original classification",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(200);
  await Promise.all([hr.c.dispose(), self.c.dispose()]);
  return { code, old: files[0], fresh: files[1] };
}
async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Documents", exact: true }).click();
  await page
    .getByRole("button", { name: "Templates and completeness", exact: true })
    .click();
}
test("HR revises template, corrects evidence and retires without deleting history", async ({
  page,
}) => {
  const f = await fixture("amelia");
  await signIn(page);
  const card = page.getByRole("article").filter({ hasText: f.code });
  await card.getByText("Template revision history", { exact: true }).click();
  await card.getByRole("button", { name: "Revise or retire template" }).click();
  await page.getByLabel("Revised template title").fill("Revised requirement");
  await page.getByRole("button", { name: "Confirm template revision" }).click();
  await expect(
    page.getByRole("alert", { name: "Document maintenance errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Revised template title")).toHaveValue(
    "Revised requirement",
  );
  await page.getByLabel("Change reason").fill("Clarify synthetic instructions");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Confirm template revision" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(card).toContainText("Current configuration revision 2");
  await expect(card).toContainText("Revised requirement");
  await page.getByLabel("Search documents").fill(f.fresh.filename);
  await page
    .locator(".document-card")
    .getByRole("button", { name: "Correct classification" })
    .click();
  await page
    .getByRole("combobox", { name: "Requirement to correct" })
    .selectOption(f.code);
  await expect(page.getByRole("dialog")).toContainText(
    "Replaces classification 1",
  );
  await page
    .getByRole("button", { name: "Confirm classification correction" })
    .click();
  await expect(
    page.getByRole("alert", { name: "Document maintenance errors" }),
  ).toBeFocused();
  await page
    .getByLabel("Change reason")
    .fill("Use the correct synthetic declaration");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page
    .getByRole("button", { name: "Confirm classification correction" })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("region", { name: "Document templates and completeness" }),
  ).toContainText("Classification 2");
  await card.getByText("Template revision history", { exact: true }).click();
  await card.getByRole("button", { name: "Revise or retire template" }).click();
  await page.getByLabel("Retire on effective date (terminal)").check();
  await page
    .getByLabel("Change reason")
    .fill("Retire synthetic requirement; retain files and audit");
  await page.getByRole("button", { name: "Confirm template revision" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(card).toContainText("RETIRED");
  await card.getByText("Template revision history", { exact: true }).click();
  await expect(card).toContainText("Revision 1");
  await expect(card).toContainText("Revision 3 · Retirement");
  await expect(
    card.getByRole("button", { name: "Revise or retire template" }),
  ).toHaveCount(0);
  await page.getByLabel("Search documents").focus();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "docs/evidence/document-template-maintenance.png",
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
test("maintenance APIs enforce ordinals, scopes, replay and concurrent single winners", async () => {
  const f = await fixture("sophia"),
    hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    other = await identity("outsider");
  try {
    const path = `/api/v1/document-templates/${f.code}`,
      input = {
        expectedVersion: 1,
        title: "Revised",
        body: "Revised synthetic text",
        required: true,
        effectiveDate: today(),
        retired: false,
        reason: "Synthetic revision",
        idempotencyKey: crypto.randomUUID(),
      };
    for (const i of [self, manager, other])
      expect(
        (
          await i.c.post(path + "/revisions", {
            headers: i.headers,
            data: input,
          })
        ).status(),
      ).toBe(403);
    expect((await other.c.get(path + "/revisions")).status()).toBe(403);
    const replies = await Promise.all([
      hr.c.post(path + "/revisions", { headers: hr.headers, data: input }),
      hr.c.post(path + "/revisions", {
        headers: hr.headers,
        data: { ...input, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect(replies.map((r) => r.status()).sort()).toEqual([200, 409]);
    await checkResponse(
      replies.find((r) => r.status() === 200)!,
      "/api/v1/document-templates/{code}/revisions",
      "post",
    );
    await checkResponse(
      await self.c.get(path + "/revisions"),
      "/api/v1/document-templates/{code}/revisions",
    );
    for (const expectedVersion of [null, 1.5])
      expect(
        (
          await hr.c.post(path + "/revisions", {
            headers: hr.headers,
            data: {
              ...input,
              expectedVersion,
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(400);
    const command = {
      employeeId: f.fresh.employeeId,
      documentId: f.fresh.id,
      expectedVersion: 1,
      expectedClassificationVersion: 1,
      reason: "Synthetic classification correction",
      idempotencyKey: crypto.randomUUID(),
    };
    for (const i of [self, manager, other])
      expect(
        (
          await i.c.post(path + "/reclassifications", {
            headers: i.headers,
            data: command,
          })
        ).status(),
      ).toBe(403);
    for (const i of [manager, other])
      expect(
        (await i.c.get(path + "/evidence-history?employeeId=E003")).status(),
      ).toBe(403);
    for (const expectedClassificationVersion of [null, 1.5])
      expect(
        (
          await hr.c.post(path + "/reclassifications", {
            headers: hr.headers,
            data: { ...command, expectedClassificationVersion },
          })
        ).status(),
      ).toBe(400);
    const commands = [
      command,
      { ...command, idempotencyKey: crypto.randomUUID() },
    ];
    const r = await Promise.all(
      commands.map((data) =>
        hr.c.post(path + "/reclassifications", { headers: hr.headers, data }),
      ),
    );
    expect(r.map((v) => v.status()).sort()).toEqual([200, 409]);
    const winner = r.findIndex((v) => v.status() === 200);
    await checkResponse(
      r[winner],
      "/api/v1/document-templates/{code}/reclassifications",
      "post",
    );
    expect(
      await (
        await hr.c.post(path + "/reclassifications", {
          headers: hr.headers,
          data: commands[winner],
        })
      ).json(),
    ).toEqual(await r[winner].json());
    const history = await self.c.get(path + "/evidence-history");
    await checkResponse(
      history,
      "/api/v1/document-templates/{code}/evidence-history",
    );
    expect(
      (await history.json()).map(
        (v: { classificationVersion: number }) => v.classificationVersion,
      ),
    ).toEqual([2, 1]);
    const state = await (
      await self.c.get("/api/v1/document-templates/completeness")
    ).json();
    expect(
      state.items.find((v: { code: string }) => v.code === f.code).rootId,
    ).toBe(f.fresh.id);
  } finally {
    await Promise.all([
      hr.c.dispose(),
      self.c.dispose(),
      manager.c.dispose(),
      other.c.dispose(),
    ]);
  }
});
