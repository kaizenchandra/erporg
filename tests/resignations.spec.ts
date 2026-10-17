import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function identity(user: string) {
  const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  const token = await (await c.get("/api/csrf")).json();
  expect(
    (
      await c.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [token.headerName]: token.token },
      })
    ).status(),
  ).toBe(204);
  const csrf = await (await c.get("/api/csrf")).json();
  return { c, headers: { [csrf.headerName]: csrf.token } };
}
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Resignations", exact: true }).click();
}
async function logout(page: Page) {
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
}
const root = "/api/v1/resignations";
test("employee submits with preserved validation, HR approves with impact, employee reads private history on mobile", async ({
  page,
}) => {
  await login(page, "sophia");
  await page
    .getByRole("button", { name: "Request my resignation", exact: true })
    .click();
  const modal = page.getByRole("dialog");
  await page
    .getByRole("button", { name: "Submit resignation request", exact: true })
    .click();
  await expect(modal.getByRole("alert")).toBeFocused();
  await expect(
    modal.getByText("A reason of 1–500 characters is required.").last(),
  ).toBeVisible();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  const date = new Date();
  date.setUTCDate(date.getUTCDate() + 30);
  await modal
    .getByLabel("Proposed last working date (required)", { exact: true })
    .fill(date.toISOString().slice(0, 10));
  await modal
    .getByLabel("Resignation reason (required)", { exact: true })
    .fill("Synthetic career transition");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(modal).toBeVisible();
  await expect(
    modal.getByLabel("Resignation reason (required)", { exact: true }),
  ).toHaveValue("Synthetic career transition");
  await modal
    .getByRole("button", { name: "Submit resignation request", exact: true })
    .click();
  await expect(modal).toHaveCount(0);
  await expect(
    page.getByRole("region", { name: "Resignations", exact: true }),
  ).toContainText("Pending review");
  await logout(page);
  await login(page, "amelia");
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
  await expect(modal).toContainText("Synthetic career transition");
  await expect(modal).toContainText("Employment status and access stay active");
  await modal
    .getByRole("button", { name: "Confirm resignation decision", exact: true })
    .click();
  await expect(modal.getByRole("alert")).toBeFocused();
  await modal
    .getByLabel("Decision comment (required)", { exact: true })
    .fill("Reviewed proposed date; exit coordination pending");
  await modal
    .getByRole("button", { name: "Confirm resignation decision", exact: true })
    .click();
  await expect(modal).toHaveCount(0);
  await expect(
    page.getByRole("region", { name: "Resignations", exact: true }),
  ).toContainText("Pending exit coordination");
  await logout(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "sophia");
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
  await expect(modal).toContainText(
    "Reviewed proposed date; exit coordination pending",
  );
  await modal.getByText("Resignation audit (2)", { exact: true }).click();
  await expect(modal).toContainText("SUBMITTED → APPROVED");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await modal.screenshot({
    path: "docs/evidence/resignation-review-mobile.png",
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
});
test("live contracts, scoped privacy, exact versions and concurrent single-winner decisions", async () => {
  const employee = await identity("marcus"),
    hr = await identity("amelia"),
    otherHr = await identity("olivia"),
    outsider = await identity("outsider"),
    sophia = await identity("sophia");
  try {
    const profile = await (
      await employee.c.get("/api/v1/employees/E002")
    ).json();
    const date = new Date();
    date.setUTCDate(date.getUTCDate() + 40);
    const command = {
      lastWorkingDate: date.toISOString().slice(0, 10),
      reason: "Synthetic API resignation",
      employeeVersion: profile.version,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const version of [null, 0.5, -1])
      expect(
        (
          await employee.c.post(root, {
            headers: employee.headers,
            data: { ...command, employeeVersion: version },
          })
        ).status(),
      ).toBe(400);
    const created = await employee.c.post(root, {
      headers: employee.headers,
      data: command,
    });
    expect(created.status()).toBe(200);
    await checkResponse(created, root, "post");
    const r = await created.json();
    expect(
      (
        await (
          await employee.c.post(root, {
            headers: employee.headers,
            data: command,
          })
        ).json()
      ).id,
    ).toBe(r.id);
    expect(
      (
        await employee.c.post(root, {
          headers: employee.headers,
          data: { ...command, reason: "Conflicting payload" },
        })
      ).status(),
    ).toBe(409);
    for (const user of [outsider, sophia]) {
      expect(
        (
          await user.c.get(`${root}/${r.id}/history`, {
            headers: { "X-Tenant-ID": "atlas", "X-Role": "HR" },
          })
        ).status(),
      ).toBe(403);
      const list = (await checkResponse(await user.c.get(root), root)) as {
        items: { employeeId: string }[];
      };
      expect(list.items.some((x) => x.employeeId === "E002")).toBe(false);
    }
    expect(
      (
        await employee.c.post(`${root}/${r.id}/decision`, {
          headers: employee.headers,
          data: {
            decision: "APPROVED",
            comment: "Forbidden self review",
            version: 0,
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(403);
    const decision = {
      decision: "APPROVED",
      comment: "Independent API review",
      version: 0,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await hr.c.post(`${root}/${r.id}/decision`, {
          headers: hr.headers,
          data: { ...decision, version: 0.5 },
        })
      ).status(),
    ).toBe(400);
    const responses = await Promise.all([
      hr.c.post(`${root}/${r.id}/decision`, {
        headers: hr.headers,
        data: decision,
      }),
      otherHr.c.post(`${root}/${r.id}/decision`, {
        headers: otherHr.headers,
        data: { ...decision, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect(responses.map((x) => x.status()).sort()).toEqual([200, 409]);
    const winner = responses[0].status() === 200 ? 0 : 1;
    await checkResponse(responses[winner], `${root}/{id}/decision`, "post");
    if (winner === 0)
      expect(
        (
          await hr.c.post(`${root}/${r.id}/decision`, {
            headers: hr.headers,
            data: decision,
          })
        ).status(),
      ).toBe(200);
    const history = (await checkResponse(
      await employee.c.get(`${root}/${r.id}/history`),
      `${root}/{id}/history`,
    )) as unknown[];
    expect(history).toHaveLength(2);
    const page = (await checkResponse(
      await hr.c.get(`${root}?page=0&size=1`),
      root,
    )) as { items: unknown[]; total: number };
    expect(page.items).toHaveLength(1);
    expect(page.total).toBeGreaterThanOrEqual(2);
    expect(
      (await (await employee.c.get("/api/v1/employees/E002")).json()).status,
    ).toBe("Active");
  } finally {
    await Promise.all(
      [employee, hr, otherHr, outsider, sophia].map((x) => x.c.dispose()),
    );
  }
});
