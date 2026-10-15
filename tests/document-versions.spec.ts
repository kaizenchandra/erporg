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
async function upload() {
  const self = await identity("sophia");
  try {
    const filename = `Version-${Date.now()}.txt`;
    const result = await self.c.post("/api/v1/documents", {
      headers: self.headers,
      data: {
        filename,
        mediaType: "text/plain",
        contentBase64: Buffer.from("Original synthetic version").toString(
          "base64",
        ),
        reason: "Original version fixture",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(result.status()).toBe(200);
    return (await result.json()) as { id: string; filename: string };
  } finally {
    await self.c.dispose();
  }
}
async function signIn(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("sophia");
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Documents", exact: true }).click();
}
test("employee uploads scanned replacement, sees immutable versions and explicitly downloads historical file", async ({
  page,
}) => {
  const d = await upload();
  await signIn(page);
  await page.getByLabel("Search documents").fill(d.filename);
  const card = page.locator(".document-card");
  await expect(card).toContainText("Version 1");
  await card.getByRole("button", { name: "Upload new version" }).click();
  await expect(page.getByRole("dialog")).toContainText(
    "Replaces latest uploaded version 1 with version 2",
  );
  await page.getByLabel("Document file").setInputFiles({
    name: d.filename,
    mimeType: "text/plain",
    buffer: Buffer.from("Second synthetic version"),
  });
  await page.getByRole("button", { name: "Upload and scan" }).click();
  await expect(
    page.getByRole("alert", { name: "Document upload errors" }),
  ).toBeFocused();
  await page
    .getByLabel("Document upload reason")
    .fill("Corrected synthetic wording");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Upload and scan" }).click();
  await expect(card).toContainText("Version 2");
  await expect(card).toHaveCount(1);
  await card.getByText("Document versions", { exact: true }).click();
  await expect(card).toContainText("Version 1 · Historical");
  const download = page.waitForEvent("download");
  await card
    .getByRole("button", { name: "Download version 1", exact: true })
    .click();
  expect((await download).suggestedFilename()).toBe(d.filename);
  await card.getByText("Document audit (latest 100)").click();
  await expect(card).toContainText("VERSION_UPLOADED");
  await page.getByLabel("Search documents").focus();
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "docs/evidence/document-version-history.png",
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
test("version API isolates records, rejects fractional/stale branches and preserves one concurrent winner and malware history", async () => {
  const old = await upload(),
    self = await identity("sophia"),
    hr = await identity("amelia"),
    manager = await identity("marcus"),
    other = await identity("outsider");
  const path = `/api/v1/documents/${old.id}/versions`,
    input = {
      expectedVersion: 1,
      upload: {
        filename: old.filename,
        mediaType: "text/plain",
        contentBase64: Buffer.from("Concurrent new version").toString("base64"),
        reason: "Revision fixture",
        idempotencyKey: crypto.randomUUID(),
      },
    };
  try {
    for (const denied of [manager, other]) {
      expect(
        (
          await denied.c.post(path, { headers: denied.headers, data: input })
        ).status(),
      ).toBe(403);
      expect((await denied.c.get(path)).status()).toBe(403);
    }
    for (const expectedVersion of [null, 1.5])
      expect(
        (
          await self.c.post(path, {
            headers: self.headers,
            data: { ...input, expectedVersion },
          })
        ).status(),
      ).toBe(400);
    const [a, b] = await Promise.all([
      self.c.post(path, { headers: self.headers, data: input }),
      hr.c.post(path, {
        headers: hr.headers,
        data: {
          ...input,
          upload: { ...input.upload, idempotencyKey: crypto.randomUUID() },
        },
      }),
    ]);
    expect([a.status(), b.status()].sort()).toEqual([200, 409]);
    const winner = a.status() === 200 ? a : b;
    await checkResponse(winner, "/api/v1/documents/{id}/versions", "post");
    const current = await winner.json();
    expect(current).toMatchObject({ version: 2, rootId: old.id });
    const chain = await self.c.get(path);
    await checkResponse(chain, "/api/v1/documents/{id}/versions");
    expect(await chain.json()).toHaveLength(2);
    expect(
      (
        await self.c.post(path, {
          headers: self.headers,
          data: {
            ...input,
            expectedVersion: 2,
            upload: { ...input.upload, idempotencyKey: crypto.randomUUID() },
          },
        })
      ).status(),
    ).toBe(409);
    const rejected = await self.c.post(
      `/api/v1/documents/${current.id}/versions`,
      {
        headers: self.headers,
        data: {
          expectedVersion: 2,
          upload: {
            ...input.upload,
            contentBase64: Buffer.from(
              "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*",
            ).toString("base64"),
            idempotencyKey: crypto.randomUUID(),
          },
        },
      },
    );
    expect(rejected.status()).toBe(200);
    await checkResponse(rejected, "/api/v1/documents/{id}/versions", "post");
    const last = await rejected.json();
    expect(last).toMatchObject({ status: "REJECTED", version: 3 });
    const list = await self.c.get(`/api/v1/documents?search=${old.filename}`);
    await checkResponse(list, "/api/v1/documents");
    expect((await list.json()).items).toMatchObject([
      { id: last.id, status: "REJECTED" },
    ]);
    expect(
      (await self.c.get(`/api/v1/documents/${last.id}/content`)).status(),
    ).toBe(409);
    expect(
      (await self.c.get(`/api/v1/documents/${old.id}/content`)).status(),
    ).toBe(200);
  } finally {
    await Promise.all([
      self.c.dispose(),
      hr.c.dispose(),
      manager.c.dispose(),
      other.c.dispose(),
    ]);
  }
});
