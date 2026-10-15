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
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Documents", exact: true }).click();
}
const today = () => new Date().toISOString().slice(0, 10);
const template = (code: string) => ({
  code,
  title: "Synthetic declaration",
  body: "Employee name:\nDeclaration:",
  required: true,
  effectiveDate: today(),
  reason: "Synthetic company requirement",
  idempotencyKey: crypto.randomUUID(),
});
async function upload(i: Awaited<ReturnType<typeof identity>>) {
  const filename = `Template-${Date.now()}.txt`;
  const r = await i.c.post("/api/v1/documents", {
    headers: i.headers,
    data: {
      filename,
      mediaType: "text/plain",
      contentBase64: Buffer.from("Synthetic completed declaration").toString(
        "base64",
      ),
      reason: "Synthetic declaration evidence",
      idempotencyKey: crypto.randomUUID(),
    },
  });
  expect(r.status()).toBe(200);
  return (await r.json()) as {
    id: string;
    filename: string;
    version: number;
    rootId: string;
  };
}
test("HR creates required text template; employee classifies document and sees completeness and audit", async ({
  page,
}) => {
  const code = `UI-${Date.now()}`;
  await signIn(page, "amelia");
  await page
    .getByRole("button", { name: "Templates and completeness", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Create document template", exact: true })
    .click();
  await page.getByLabel("Template code", { exact: false }).fill(code);
  await page
    .getByRole("button", { name: "Create template", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Document template errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Template code", { exact: false })).toHaveValue(
    code,
  );
  await page
    .getByLabel("Template title", { exact: false })
    .fill("Synthetic declaration");
  await page
    .getByLabel("Template text", { exact: false })
    .fill("Employee name:\nDeclaration:");
  await page.getByLabel("Effective date", { exact: false }).fill(today());
  await page.getByLabel("Required for completeness").check();
  await page
    .getByLabel("Configuration reason", { exact: false })
    .fill("Synthetic company configuration");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page
    .getByRole("button", { name: "Create template", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("article").filter({ hasText: code }),
  ).toContainText("MISSING");
  await page.getByLabel("Employee ID for completeness").fill("E003");
  await page
    .getByRole("button", { name: "Check employee", exact: true })
    .click();
  await expect(
    page.getByRole("region", { name: "Document templates and completeness" }),
  ).toContainText("Employee E003");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  const own = await identity("sophia");
  const d = await upload(own);
  await own.c.dispose();
  await signIn(page, "sophia");
  await page
    .getByRole("button", { name: "Templates and completeness", exact: true })
    .click();
  await expect(
    page.getByRole("article").filter({ hasText: code }),
  ).toContainText("MISSING");
  await page.getByLabel("Search documents").fill(d.filename);
  const card = page.locator(".document-card");
  await card
    .getByRole("button", { name: "Link to template", exact: true })
    .click();
  await page
    .getByRole("combobox", { name: "Document template" })
    .selectOption(code);
  await page
    .getByRole("button", { name: "Confirm document link", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Document template errors" }),
  ).toBeFocused();
  await page
    .getByLabel("Configuration reason", { exact: false })
    .fill("Classify this synthetic completed declaration");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page
    .getByRole("button", { name: "Confirm document link", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(
    page.getByRole("article").filter({ hasText: code }),
  ).toContainText("PRESENT");
  await expect(
    page.getByRole("region", { name: "Document templates and completeness" }),
  ).toContainText("Linked version 1 · sophia");
  await page.getByLabel("Search documents").focus();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "docs/evidence/document-template-completeness.png",
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
test("template API isolates configuration/evidence, handles concurrent links and latest blocked version", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const c = template(`API-${Date.now()}`);
    for (const i of [self, manager])
      expect(
        (
          await i.c.post("/api/v1/document-templates", {
            headers: i.headers,
            data: c,
          })
        ).status(),
      ).toBe(403);
    const [a, b] = await Promise.all([
      hr.c.post("/api/v1/document-templates", { headers: hr.headers, data: c }),
      hr.c.post("/api/v1/document-templates", { headers: hr.headers, data: c }),
    ]);
    expect([a.status(), b.status()]).toEqual([200, 200]);
    await checkResponse(a, "/api/v1/document-templates", "post");
    expect(await a.json()).toEqual(await b.json());
    expect(
      (
        await hr.c.post("/api/v1/document-templates", {
          headers: hr.headers,
          data: { ...c, title: "Conflicting title" },
        })
      ).status(),
    ).toBe(409);
    await checkResponse(
      await self.c.get("/api/v1/document-templates"),
      "/api/v1/document-templates",
    );
    const d = await upload(self),
      path = `/api/v1/document-templates/${c.code}/evidence`,
      command = {
        documentId: d.id,
        expectedVersion: 1,
        reason: "Synthetic classification",
        idempotencyKey: crypto.randomUUID(),
      };
    for (const i of [manager, outside]) {
      expect(
        (await i.c.post(path, { headers: i.headers, data: command })).status(),
      ).toBe(403);
      expect(
        (
          await i.c.get(
            "/api/v1/document-templates/completeness?employeeId=E003",
          )
        ).status(),
      ).toBe(403);
      expect(
        (
          await i.c.get("/api/v1/document-templates/evidence?employeeId=E003")
        ).status(),
      ).toBe(403);
    }
    for (const v of [null, 1.5])
      expect(
        (
          await self.c.post(path, {
            headers: self.headers,
            data: { ...command, expectedVersion: v },
          })
        ).status(),
      ).toBe(400);
    const commands = [
      command,
      { ...command, idempotencyKey: crypto.randomUUID() },
    ];
    const replies = await Promise.all(
      commands.map((data) =>
        self.c.post(path, { headers: self.headers, data }),
      ),
    );
    expect(replies.map((r) => r.status()).sort()).toEqual([200, 409]);
    const winner = replies.findIndex((r) => r.status() === 200);
    await checkResponse(
      replies[winner],
      "/api/v1/document-templates/{code}/evidence",
      "post",
    );
    expect(
      await (
        await self.c.post(path, {
          headers: self.headers,
          data: commands[winner],
        })
      ).json(),
    ).toEqual(await replies[winner].json());
    const p = await hr.c.get(
      "/api/v1/document-templates/completeness?employeeId=E003",
    );
    await checkResponse(p, "/api/v1/document-templates/completeness");
    expect(
      (await p.json()).items.find((t: { code: string }) => t.code === c.code)
        .state,
    ).toBe("PRESENT");
    await checkResponse(
      await self.c.get("/api/v1/document-templates/evidence"),
      "/api/v1/document-templates/evidence",
    );
    const malware =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    const next = await self.c.post(`/api/v1/documents/${d.id}/versions`, {
      headers: self.headers,
      data: {
        expectedVersion: 1,
        upload: {
          filename: "Blocked.txt",
          mediaType: "text/plain",
          contentBase64: Buffer.from(malware).toString("base64"),
          reason: "Synthetic malware replacement",
          idempotencyKey: crypto.randomUUID(),
        },
      },
    });
    expect(next.status()).toBe(200);
    expect((await next.json()).status).toBe("REJECTED");
    const blocked = await self.c.get("/api/v1/document-templates/completeness");
    await checkResponse(blocked, "/api/v1/document-templates/completeness");
    expect(
      (await blocked.json()).items.find(
        (t: { code: string }) => t.code === c.code,
      ),
    ).toMatchObject({ state: "BLOCKED", version: 2, scanStatus: "REJECTED" });
  } finally {
    await Promise.all([
      hr.c.dispose(),
      self.c.dispose(),
      manager.c.dispose(),
      outside.c.dispose(),
    ]);
  }
});
