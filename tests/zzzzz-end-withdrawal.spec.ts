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
  const csrf = await (await c.get("/api/csrf")).json();
  return { c, headers: { [csrf.headerName]: csrf.token } };
}
async function path(hr: Awaited<ReturnType<typeof identity>>) {
  const list = await (
    await hr.c.get("/api/v1/resignations?status=APPROVED&size=100")
  ).json();
  const r = list.items.find(
    (r: { employeeId: string }) => r.employeeId === "E003",
  );
  expect(r, "Prior employment-end workflow fixture").toBeDefined();
  return `/api/v1/resignations/${r.id}/employment-end`;
}
const endpoint = "/api/v1/resignations/{id}/employment-end/withdrawals";
test("withdrawal contracts, role isolation, exact versions, stable replay and concurrent independent rejection preserve cutoff", async () => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    rival = await identity("olivia"),
    employee = await identity("sophia"),
    outsider = await identity("outsider");
  try {
    const p = await path(hr),
      ctx = (await checkResponse(
        await hr.c.get(p),
        "/api/v1/resignations/{id}/employment-end",
      )) as { employeeVersion: number; phase: string };
    expect(ctx.phase).toBe("SCHEDULED");
    const command = {
      reason: "Synthetic withdrawal requiring independent review",
      version: ctx.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const c of [employee, outsider])
      expect(
        (
          await c.c.post(p + "/withdrawals", {
            headers: { ...c.headers, "X-Role": "HR", "X-Tenant-ID": "atlas" },
            data: command,
          })
        ).status(),
      ).toBe(403);
    for (const version of [null, 0.5, -1])
      expect(
        (
          await hr.c.post(p + "/withdrawals", {
            headers: hr.headers,
            data: { ...command, version },
          })
        ).status(),
      ).toBe(400);
    const w = (await checkResponse(
      await hr.c.post(p + "/withdrawals", {
        headers: hr.headers,
        data: command,
      }),
      endpoint,
      "post",
    )) as { id: string; status: string };
    expect(w.status).toBe("SUBMITTED");
    expect(
      (
        await (
          await hr.c.post(p + "/withdrawals", {
            headers: hr.headers,
            data: command,
          })
        ).json()
      ).id,
    ).toBe(w.id);
    expect(
      (
        await hr.c.post(p + "/withdrawals", {
          headers: hr.headers,
          data: { ...command, reason: "Changed replay" },
        })
      ).status(),
    ).toBe(409);
    const reviewPath = p + `/withdrawals/${w.id}/review`,
      review = {
        decision: "REJECTED",
        reason: "Independent rejection; cutoff stays",
        version: ctx.employeeVersion,
        idempotencyKey: crypto.randomUUID(),
      };
    expect(
      (
        await hr.c.post(reviewPath, { headers: hr.headers, data: review })
      ).status(),
    ).toBe(403);
    const other = { ...review, idempotencyKey: crypto.randomUUID() };
    const responses = await Promise.all([
      reviewer.c.post(reviewPath, { headers: reviewer.headers, data: review }),
      rival.c.post(reviewPath, { headers: rival.headers, data: other }),
    ]);
    expect(responses.map((r) => r.status()).sort()).toEqual([200, 409]);
    await checkResponse(
      responses.find((r) => r.status() === 200)!,
      endpoint + "/{withdrawalId}/review",
      "post",
    );
    const winning = responses[0].status() === 200 ? review : other;
    expect(
      (
        await reviewer.c.post(reviewPath, {
          headers: reviewer.headers,
          data: winning,
        })
      ).status(),
    ).toBe(200);
    expect(
      (
        await reviewer.c.post(reviewPath, {
          headers: reviewer.headers,
          data: { ...winning, decision: "APPROVED" },
        })
      ).status(),
    ).toBe(409);
    expect((await (await hr.c.get(p)).json()).phase).toBe("SCHEDULED");
  } finally {
    await Promise.all(
      [hr, reviewer, rival, employee, outsider].map((x) => x.c.dispose()),
    );
  }
});
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await page.getByRole("button", { name: "Resignations", exact: true }).click();
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
}
test("independent HR withdrawal UI preserves validation and unsaved reason, displays decision context and resumes coordination", async ({
  page,
}) => {
  await login(page, "amelia");
  const panel = page.getByRole("region", {
    name: "Employment end withdrawal",
    exact: true,
  });
  await panel
    .getByRole("button", { name: "Request end withdrawal", exact: true })
    .click();
  await panel
    .getByRole("button", { name: "Submit withdrawal request", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByLabel("Withdrawal reason (required)", { exact: true })
    .fill("Employee and HR agreed continued employment; review independently");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    panel.getByLabel("Withdrawal reason (required)", { exact: true }),
  ).toHaveValue(
    "Employee and HR agreed continued employment; review independently",
  );
  await panel
    .getByRole("button", { name: "Submit withdrawal request", exact: true })
    .click();
  await expect(panel).toContainText("SUBMITTED");
  await expect(panel).toContainText("Another HR employee must review");
  await expect(
    panel.getByRole("button", { name: "Review end withdrawal", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await login(page, "olivia");
  await panel
    .getByRole("button", { name: "Review end withdrawal", exact: true })
    .click();
  await expect(panel).toContainText("No external action is reversed");
  await panel
    .getByLabel("Withdrawal reason (required)", { exact: true })
    .fill("Independent HR confirms continuation before cutoff");
  await panel
    .getByRole("button", { name: "Record withdrawal decision", exact: true })
    .click();
  await expect(panel).toContainText(
    "Local inactivity and its pending handoffs are suppressed",
  );
  await expect(panel).toContainText("Reviewed by olivia");
  await expect(
    page.getByRole("button", { name: "Assign exit task", exact: true }),
  ).toBeVisible();
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
  await panel.screenshot({ path: "docs/evidence/end-withdrawal-mobile.png" });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
  await expect(panel).toContainText(
    "Independent HR confirms continuation before cutoff",
  );
});
