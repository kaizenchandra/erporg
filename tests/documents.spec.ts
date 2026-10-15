import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Documents", exact: true }).click();
}
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
test("private upload scan download and accessible document error recovery", async ({
  page,
}) => {
  const filename = `Synthetic-${Date.now()}.txt`,
    text = "Synthetic private employment letter";
  await signIn(page, "sophia");
  await page.getByRole("button", { name: "Upload your document" }).click();
  await page.getByLabel("Document file").setInputFiles({
    name: filename,
    mimeType: "text/plain",
    buffer: Buffer.from(text),
  });
  await page.getByRole("button", { name: "Upload and scan" }).click();
  await expect(
    page.getByRole("alert", { name: "Document upload errors" }),
  ).toBeFocused();
  expect(
    await page
      .getByLabel("Document file")
      .evaluate((input: HTMLInputElement) => input.files?.[0]?.name),
  ).toBe(filename);
  await page
    .getByLabel("Document upload reason")
    .fill("Synthetic document verification");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Upload and scan" }).click();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "Document uploaded: AVAILABLE" }),
  ).toBeVisible();
  await page.getByLabel("Search documents").fill(filename);
  const card = page.locator(".document-card");
  await expect(card).toContainText("AVAILABLE");
  const download = page.waitForEvent("download");
  await card.getByRole("button", { name: "Download document" }).click();
  expect((await download).suggestedFilename()).toBe(filename);
  await card.getByText("Document audit (latest 100)").click();
  await expect(card).toContainText("DOWNLOAD_AUTHORISED");
  await page.screenshot({
    path: "docs/evidence/private-documents.png",
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
test("real scanning rejects EICAR and APIs isolate document records with replay and concurrent upload", async () => {
  const own = await identity("sophia"),
    hr = await identity("amelia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const input = {
      filename: `API-${Date.now()}.txt`,
      mediaType: "text/plain",
      contentBase64: Buffer.from("Synthetic clean content").toString("base64"),
      reason: "Synthetic upload API",
      idempotencyKey: crypto.randomUUID(),
    };
    const [a, b] = await Promise.all([
      own.c.post("/api/v1/documents", { headers: own.headers, data: input }),
      own.c.post("/api/v1/documents", { headers: own.headers, data: input }),
    ]);
    expect([a.status(), b.status()]).toEqual([200, 200]);
    await checkResponse(a, "/api/v1/documents", "post");
    const d = await a.json();
    expect((await b.json()).id).toBe(d.id);
    expect(d.status).toBe("AVAILABLE");
    const bytes = await own.c.get(`/api/v1/documents/${d.id}/content`);
    expect(bytes.status()).toBe(200);
    expect((await bytes.body()).toString()).toBe("Synthetic clean content");
    expect(bytes.headers()["cache-control"]).toContain("no-store");
    expect(bytes.headers()["x-content-type-options"]).toBe("nosniff");
    expect(bytes.headers()["content-disposition"]).toContain("attachment");
    await checkResponse(
      await hr.c.get(`/api/v1/documents/${d.id}/history`),
      "/api/v1/documents/{id}/history",
    );
    for (const denied of [manager, outside]) {
      expect(
        (await denied.c.get(`/api/v1/documents/${d.id}/content`)).status(),
      ).toBe(403);
      expect(
        (await denied.c.get(`/api/v1/documents/${d.id}/history`)).status(),
      ).toBe(403);
      const list = await denied.c.get(
        `/api/v1/documents?search=${input.filename}`,
      );
      await checkResponse(list, "/api/v1/documents");
      expect((await list.json()).total).toBe(0);
    }
    expect(
      (
        await own.c.post("/api/v1/documents", {
          headers: own.headers,
          data: { ...input, reason: "Changed" },
        })
      ).status(),
    ).toBe(409);
    const eicar =
      "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
    const infected = await own.c.post("/api/v1/documents", {
      headers: own.headers,
      data: {
        ...input,
        filename: `EICAR-${Date.now()}.txt`,
        contentBase64: Buffer.from(eicar).toString("base64"),
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(infected.status()).toBe(200);
    await checkResponse(infected, "/api/v1/documents", "post");
    const rejected = await infected.json();
    expect(rejected.status).toBe("REJECTED");
    expect(
      (await own.c.get(`/api/v1/documents/${rejected.id}/content`)).status(),
    ).toBe(409);
    expect(
      (
        await own.c.post("/api/v1/documents", {
          headers: own.headers,
          data: {
            ...input,
            filename: "../bad.txt",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(400);
    expect((await own.c.get("/api/v1/documents?size=101")).status()).toBe(400);
  } finally {
    await Promise.all([
      own.c.dispose(),
      hr.c.dispose(),
      manager.c.dispose(),
      outside.c.dispose(),
    ]);
  }
});
