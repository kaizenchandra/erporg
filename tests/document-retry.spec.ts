import { test, expect, request, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
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
async function quarantines(texts: string[]) {
  const self = await identity("sophia");
  const items: { id: string; filename: string }[] = [];
  try {
    execFileSync(
      "docker",
      ["compose", "--profile", "documents", "stop", "document-scanner"],
      { timeout: 30000, stdio: "pipe" },
    );
    try {
      for (const text of texts) {
        const filename = `Retry-${Date.now()}-${items.length}.txt`;
        const result = await self.c.post("/api/v1/documents", {
          headers: self.headers,
          data: {
            filename,
            mediaType: "text/plain",
            contentBase64: Buffer.from(text).toString("base64"),
            reason: "Controlled local scanner outage fixture",
            idempotencyKey: crypto.randomUUID(),
          },
        });
        expect(result.status()).toBe(200);
        const d = await result.json();
        expect(d.status).toBe("QUARANTINED");
        items.push({ id: d.id, filename: d.filename });
      }
    } finally {
      execFileSync(
        "docker",
        [
          "compose",
          "--profile",
          "documents",
          "up",
          "-d",
          "--wait",
          "--wait-timeout",
          "90",
          "document-scanner",
        ],
        { timeout: 95000, stdio: "pipe" },
      );
    }
  } finally {
    await self.c.dispose();
  }
  return items;
}
test("HR rescans quarantined stored document after real outage; original history remains", async ({
  page,
}) => {
  test.setTimeout(120000);
  const [d] = await quarantines(["Synthetic recoverable document"]);
  await signIn(page, "amelia");
  await page.getByLabel("Search documents").fill(d.filename);
  const card = page.locator(".document-card");
  await expect(card).toContainText("QUARANTINED");
  await card.getByRole("button", { name: "Retry document scan" }).click();
  await expect(page.getByRole("dialog")).toContainText("attempt 1 of 3");
  await page.getByRole("button", { name: "Confirm scan retry" }).click();
  await expect(
    page.getByRole("alert", { name: "Document scan retry errors" }),
  ).toBeFocused();
  await page
    .getByLabel("Scan retry reason")
    .fill("Scanner recovered; recheck original bytes");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Confirm scan retry" }).click();
  await expect(card).toContainText("AVAILABLE");
  await expect(card).toContainText("Scan retries 1 of 3");
  await expect(
    card.getByRole("button", { name: "Retry document scan" }),
  ).toHaveCount(0);
  await card.getByText("Document audit (latest 100)").click();
  await expect(card).toContainText("RESCAN_AVAILABLE");
  await expect(card).toContainText("QUARANTINED");
  const download = page.waitForEvent("download");
  await card.getByRole("button", { name: "Download document" }).click();
  expect((await download).suggestedFilename()).toBe(d.filename);
  await page.screenshot({
    path: "docs/evidence/document-rescan.png",
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
test("retry API denies ordinary roles and fractional versions; concurrent retries commit one result; malware stays blocked", async () => {
  test.setTimeout(120000);
  const [d, malware] = await quarantines([
    "Concurrent retry fixture",
    "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*",
  ]);
  const hr = await identity("amelia"),
    peer = await identity("olivia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    other = await identity("outsider");
  const path = `/api/v1/documents/${d.id}/scan-retries`,
    input = {
      expectedAttempt: 0,
      reason: "Recovered scanner verification",
      idempotencyKey: crypto.randomUUID(),
    };
  try {
    for (const denied of [self, manager, other])
      expect(
        (
          await denied.c.post(path, { headers: denied.headers, data: input })
        ).status(),
      ).toBe(403);
    for (const expectedAttempt of [null, 0.5])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...input, expectedAttempt },
          })
        ).status(),
      ).toBe(400);
    const [a, b] = await Promise.all([
      hr.c.post(path, { headers: hr.headers, data: input }),
      peer.c.post(path, {
        headers: peer.headers,
        data: { ...input, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect([a.status(), b.status()].sort()).toEqual([200, 409]);
    const winner = a.status() === 200 ? a : b;
    await checkResponse(winner, "/api/v1/documents/{id}/scan-retries", "post");
    const result = await winner.json();
    expect(result).toMatchObject({ status: "AVAILABLE", scanAttempts: 1 });
    const history = await checkResponse(
      await self.c.get(`/api/v1/documents/${d.id}/history`),
      "/api/v1/documents/{id}/history",
    );
    expect(
      (history as { action: string }[]).filter(
        (x) => x.action === "RESCAN_AVAILABLE",
      ),
    ).toHaveLength(1);
    if (a.status() === 200) {
      const replay = await hr.c.post(path, {
        headers: hr.headers,
        data: input,
      });
      expect(replay.status()).toBe(200);
      expect(await replay.json()).toEqual(result);
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...input, reason: "Conflicting replay" },
          })
        ).status(),
      ).toBe(409);
    }
    const rejected = await hr.c.post(
      `/api/v1/documents/${malware.id}/scan-retries`,
      { headers: hr.headers, data: input },
    );
    expect(rejected.status()).toBe(200);
    await checkResponse(
      rejected,
      "/api/v1/documents/{id}/scan-retries",
      "post",
    );
    expect((await rejected.json()).status).toBe("REJECTED");
    expect(
      (await self.c.get(`/api/v1/documents/${malware.id}/content`)).status(),
    ).toBe(409);
  } finally {
    await Promise.all([
      hr.c.dispose(),
      peer.c.dispose(),
      self.c.dispose(),
      manager.c.dispose(),
      other.c.dispose(),
    ]);
  }
});
