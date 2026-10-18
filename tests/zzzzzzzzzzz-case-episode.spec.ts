import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { CaseEpisode } from "@/features/people/case-episode";
const root = "/api/v1/resignations",
  contract = root + "/{id}/episode";
type Case = {
  id: string;
  employeeId: string;
  status: string;
  recordedAt: string;
};
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
  const token = await (await c.get("/api/csrf")).json();
  return { c, headers: { [token.headerName]: token.token } };
}
async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  const done = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/login" &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  expect((await done).status()).toBe(204);
  await expect(page.getByLabel("Workspace account")).toHaveCount(0);
  await page.getByRole("button", { name: "Resignations", exact: true }).click();
}
test("case episode contract is private, atomic and stable through command replay and withdrawal", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia"),
    manager = await identity("marcus"),
    other = await identity("outsider"),
    owner = await identity("amelia");
  try {
    const cases = (await (await hr.c.get(root + "?size=100")).json())
      .items as Case[];
    const r = cases.find(
      (r) => r.employeeId === "E003" && r.status === "APPROVED",
    )!;
    expect(r).toBeTruthy();
    const path = `${root}/${r.id}/episode`;
    const response = await hr.c.get(path);
    await checkResponse(response, contract);
    const saved = await response.json();
    expect(saved.episodeNumber).toBe(1);
    expect(saved.origin).toBe("COMMAND");
    expect(saved.sourceRecordedAt).toBe(r.recordedAt);
    expect(saved.linkedAt).toBe(saved.sourceRecordedAt);
    expect(await (await self.c.get(path)).json()).toEqual(saved);
    for (const actor of [manager, other])
      expect(
        (
          await actor.c.get(path, {
            headers: { "X-Role": "HR", "X-Tenant": "atlas" },
          })
        ).status(),
      ).toBe(403);
    expect((await hr.c.get(root + "/missing-case/episode")).status()).toBe(403);
    const employee = await (await owner.c.get("/api/v1/employees/E001")).json();
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + 30);
    const command = {
      lastWorkingDate: d.toISOString().slice(0, 10),
      reason: "Verify case episode ownership",
      employeeVersion: employee.version,
      idempotencyKey: crypto.randomUUID(),
    };
    const made = await owner.c.post(root, {
      headers: owner.headers,
      data: command,
    });
    expect(made.status()).toBe(200);
    const fresh = await made.json();
    const linked = await owner.c.get(`${root}/${fresh.id}/episode`);
    await checkResponse(linked, contract);
    const reference = await linked.json();
    expect(reference.correlationId).toBe(made.headers()["x-correlation-id"]);
    expect(reference.linkedBy).toBe("amelia");
    expect(reference.episodeStartDate).toBe(employee.startDate);
    expect(
      (
        await owner.c.post(root, { headers: owner.headers, data: command })
      ).status(),
    ).toBe(200);
    expect(
      (
        await owner.c.post(`${root}/${fresh.id}/withdrawal`, {
          headers: owner.headers,
          data: {
            reason: "Finish synthetic provenance check",
            version: 0,
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    expect(
      await (await owner.c.get(`${root}/${fresh.id}/episode`)).json(),
    ).toEqual(reference);
    expect((await self.c.get(`${root}/${fresh.id}/episode`)).status()).toBe(
      403,
    );
  } finally {
    await Promise.all(
      [hr, self, manager, other, owner].map((x) => x.c.dispose()),
    );
  }
});
test("case UI keeps original ownership after rehire and recovers loading/failure with authorised real data", async ({
  page,
}) => {
  await login(page);
  const cases = (await (await page.request.get(root + "?size=100")).json())
    .items as Case[];
  const r = cases.find(
    (r) => r.employeeId === "E-RETURN-TEST" && r.status === "APPROVED",
  )!;
  expect(r).toBeTruthy();
  const path = `${root}/${r.id}/episode`,
    reference = (await (await page.request.get(path)).json()) as CaseEpisode;
  const profile = await (
    await page.request.get("/api/v1/employees/E-RETURN-TEST")
  ).json();
  expect(reference.episodeStartDate < profile.startDate).toBe(true);
  expect(profile.status).toBe("Active");
  let release: () => void = () => {};
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route(
    "**" + path,
    async (route) => {
      await gate;
      await route.fulfill({
        status: 503,
        contentType: "application/problem+json",
        body: JSON.stringify({
          detail: "Episode ownership temporarily unavailable",
        }),
      });
    },
    { times: 1 },
  );
  await page
    .getByRole("button", {
      name: "Review resignation E-RETURN-TEST",
      exact: true,
    })
    .click();
  const panel = page.getByRole("region", {
    name: "Case employment episode",
    exact: true,
  });
  await expect(panel.getByRole("status")).toContainText(
    "Loading episode ownership",
  );
  release();
  await expect(panel.getByRole("alert")).toBeFocused();
  await expect(panel).toContainText("temporarily unavailable");
  const response = page.waitForResponse(
    (resp) => new URL(resp.url()).pathname === path && resp.status() === 200,
  );
  await panel
    .getByRole("button", { name: "Retry episode ownership", exact: true })
    .click();
  await checkResponse(await response, contract);
  await expect(panel).toContainText("Original employment · 1");
  await panel.getByText("Episode link history", { exact: true }).click();
  await expect(panel).toContainText("captured with this submission");
  await expect(panel).toContainText(reference.linkedBy);
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
  await panel.scrollIntoViewIfNeeded();
  await page.screenshot({ path: "docs/evidence/case-episode-mobile.png" });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Close panel", exact: true })
    .click();
  // Closing an in-flight ownership read cancels it; no stale result reopens the panel.
  let resume: () => void = () => {};
  const pending = new Promise<void>((resolve) => {
    resume = resolve;
  });
  await page.route(
    "**" + path,
    async (route) => {
      await pending;
      await route.continue().catch(() => {});
    },
    { times: 1 },
  );
  await page
    .getByRole("button", {
      name: "Review resignation E-RETURN-TEST",
      exact: true,
    })
    .click();
  await expect(panel.getByRole("status")).toContainText(
    "Loading episode ownership",
  );
  const aborted = page.waitForEvent("requestfailed", {
    predicate: (r) => new URL(r.url()).pathname === path,
  });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Close panel", exact: true })
    .click();
  resume();
  await aborted;
  await expect(panel).toHaveCount(0);
});
if (process.env.EPISODE_UPGRADE_TEST === "1") {
  test("legacy links remain visibly distinct from the original submission instant", async ({
    page,
  }) => {
    await login(page);
    const cases = (await (await page.request.get(root + "?size=100")).json())
      .items as Case[];
    const r = cases.find(
      (r) => r.employeeId === "E003" && r.status === "APPROVED",
    )!;
    expect(r).toBeTruthy();
    const response = await page.request.get(`${root}/${r.id}/episode`);
    await checkResponse(response, contract);
    const link = await response.json();
    expect(link.origin).toBe("MIGRATED_V42");
    expect(link.sourceRecordedAt).toBe(r.recordedAt);
    expect(link.linkedAt).not.toBe(link.sourceRecordedAt);
    expect(link.linkedBy).toBe("SYSTEM_MIGRATION_V43");
    await page
      .getByRole("button", { name: "Review resignation E003", exact: true })
      .click();
    const panel = page.getByRole("region", {
      name: "Case employment episode",
      exact: true,
    });
    await panel.getByText("Episode link history", { exact: true }).click();
    await expect(panel).toContainText(
      "captured later; the submission timestamp is unchanged",
    );
    await page.screenshot({ path: "docs/evidence/case-episode-upgrade.png" });
  });
}
