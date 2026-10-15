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
const later = (days: number) =>
  new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);
async function fixture() {
  const self = await identity("sophia");
  try {
    const filename = `Retention-${Date.now()}.txt`;
    const r = await self.c.post("/api/v1/documents", {
      headers: self.headers,
      data: {
        filename,
        mediaType: "text/plain",
        contentBase64: Buffer.from("Synthetic preservation record").toString(
          "base64",
        ),
        reason: "Synthetic retention fixture",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(r.status()).toBe(200);
    return (await r.json()) as { id: string; filename: string; rootId: string };
  } finally {
    await self.c.dispose();
  }
}
async function signIn(page: Page, user: string, filename: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: "Documents", exact: true }).click();
  await page.getByLabel("Search documents").fill(filename);
  await page
    .locator(".document-card")
    .getByRole("button", { name: "Retention and legal holds" })
    .click();
}
function command(action: string, expectedVersion: number) {
  return {
    action,
    expectedVersion,
    retainUntil: null as string | null,
    basis: null as string | null,
    holdId: null as string | null,
    caseReference: null as string | null,
    reason: "Synthetic governance reason",
    idempotencyKey: crypto.randomUUID(),
  };
}
test("HR records explicit retention and hold; independent HR releases with private audit", async ({
  page,
}) => {
  const d = await fixture();
  await signIn(page, "amelia", d.filename);
  await expect(page.getByRole("dialog")).toContainText("NO POLICY");
  await page
    .getByRole("button", { name: "Set retention directive", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Confirm retention directive" })
    .click();
  await expect(
    page.getByRole("alert", { name: "Document governance errors" }),
  ).toBeFocused();
  await page.getByLabel("Retain until").fill(today());
  await page
    .getByLabel("Policy basis")
    .fill("Explicit synthetic company instruction; no statutory rule inferred");
  await page
    .getByLabel("Governance reason")
    .fill("Record authorised demonstration directive");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page
    .getByRole("button", { name: "Confirm retention directive" })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Governance version 1");
  await page
    .getByRole("button", { name: "Place legal hold", exact: true })
    .click();
  await page.getByLabel("Case reference").fill("SYNTHETIC-CASE-UI");
  await page.getByRole("button", { name: "Confirm legal hold" }).click();
  await expect(
    page.getByRole("alert", { name: "Document governance errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Case reference")).toHaveValue(
    "SYNTHETIC-CASE-UI",
  );
  await page
    .getByLabel("Governance reason")
    .fill("Preserve all versions for synthetic review");
  await page.getByRole("button", { name: "Confirm legal hold" }).click();
  await expect(page.getByRole("dialog")).toContainText("ON HOLD");
  await expect(page.getByRole("dialog")).toContainText(
    "A different HR employee must release",
  );
  await expect(
    page.getByRole("button", { name: "Release hold for SYNTHETIC-CASE-UI" }),
  ).toHaveCount(0);
  await page.screenshot({
    path: "docs/evidence/document-retention-hold.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Close panel" }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await signIn(page, "olivia", d.filename);
  await page
    .getByRole("button", { name: "Release hold for SYNTHETIC-CASE-UI" })
    .click();
  await page
    .getByLabel("Governance reason")
    .fill("Independent HR review permits release of this hold only");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.getByRole("button", { name: "Confirm hold release" }).click();
  await expect(page.getByRole("dialog")).toContainText(
    "SYNTHETIC-CASE-UI · Released",
  );
  await expect(page.getByRole("dialog")).toContainText("RETAIN");
  await expect(page.getByRole("dialog")).toContainText(
    "Event 3 · RELEASE_HOLD",
  );
  await expect(
    page.getByText("Event 3 · RELEASE_HOLD", { exact: true }).locator(".."),
  ).toContainText("Case: SYNTHETIC-CASE-UI");
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
test("governance API denies private access and self release, preserves replay and one concurrent winner", async () => {
  const d = await fixture(),
    hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const path = `/api/v1/documents/${d.id}/retention`;
    const input = {
      ...command("SET_POLICY", 0),
      retainUntil: today(),
      basis: "Explicit synthetic basis",
    };
    const commands = [
      input,
      { ...command("PLACE_HOLD", 0), caseReference: "SYNTHETIC-RACE" },
    ];
    const replies = await Promise.all(
      commands.map((data) => hr.c.post(path, { headers: hr.headers, data })),
    );
    expect(replies.map((r) => r.status()).sort()).toEqual([200, 409]);
    const winner = replies.findIndex((r) => r.status() === 200);
    await checkResponse(
      replies[winner],
      "/api/v1/documents/{id}/retention",
      "post",
    );
    const second = {
      ...command("SET_POLICY", 1),
      retainUntil: later(2),
      basis: "Explicit extended company basis",
    };
    expect(
      (await hr.c.post(path, { headers: hr.headers, data: second })).status(),
    ).toBe(200);
    expect(
      await (
        await hr.c.post(path, { headers: hr.headers, data: commands[winner] })
      ).json(),
    ).toEqual(await replies[winner].json());
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: {
            ...command("SET_POLICY", 2),
            retainUntil: today(),
            basis: "Shortened policy",
          },
        })
      ).status(),
    ).toBe(409);
    for (const i of [self, manager, outside]) {
      expect((await i.c.get(path)).status()).toBe(403);
      expect(
        (
          await i.c.post(path, {
            headers: i.headers,
            data: command("PLACE_HOLD", 2),
          })
        ).status(),
      ).toBe(403);
    }
    for (const expectedVersion of [null, 1.5])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: {
              ...command("PLACE_HOLD", 2),
              expectedVersion,
              caseReference: "SYNTHETIC-INVALID",
            },
          })
        ).status(),
      ).toBe(400);
    const hold = await hr.c.post(path, {
      headers: hr.headers,
      data: {
        ...command("PLACE_HOLD", 2),
        caseReference: "PRIVATE-SYNTHETIC-CASE",
      },
    });
    expect(hold.status()).toBe(200);
    const placed = await hold.json();
    const release = { ...command("RELEASE_HOLD", 3), holdId: placed.holdId };
    expect(
      (await hr.c.post(path, { headers: hr.headers, data: release })).status(),
    ).toBe(403);
    expect(
      (
        await reviewer.c.post(path, {
          headers: reviewer.headers,
          data: release,
        })
      ).status(),
    ).toBe(200);
    const review = await hr.c.get(path);
    await checkResponse(review, "/api/v1/documents/{id}/retention");
    expect((await review.json()).deletionAvailable).toBe(false);
    expect(
      (await review.json()).holds.find(
        (h: { id: string }) => h.id === placed.holdId,
      ).active,
    ).toBe(false);
    const next = await self.c.post(`/api/v1/documents/${d.id}/versions`, {
      headers: self.headers,
      data: {
        expectedVersion: 1,
        upload: {
          filename: "Retention-next.txt",
          mediaType: "text/plain",
          contentBase64: Buffer.from("Synthetic next version").toString(
            "base64",
          ),
          reason: "Synthetic retained replacement",
          idempotencyKey: crypto.randomUUID(),
        },
      },
    });
    expect(next.status()).toBe(200);
    const v = await next.json();
    expect(
      await (await hr.c.get(`/api/v1/documents/${v.id}/retention`)).json(),
    ).toEqual(await review.json());
    const history = await (
      await self.c.get(`/api/v1/documents/${d.id}/history`)
    ).json();
    expect(JSON.stringify(history)).not.toContain("PRIVATE-SYNTHETIC-CASE");
    expect(JSON.stringify(history)).not.toContain("RELEASE_HOLD");
  } finally {
    await Promise.all([
      hr.c.dispose(),
      reviewer.c.dispose(),
      self.c.dispose(),
      manager.c.dispose(),
      outside.c.dispose(),
    ]);
  }
});
