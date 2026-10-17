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
async function login(page: Page, user: string, tab: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: tab, exact: true }).click();
}
async function logout(page: Page) {
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
}
async function caseId(hr: Awaited<ReturnType<typeof identity>>) {
  const response = await hr.c.get(
    "/api/v1/resignations?status=APPROVED&size=100",
  );
  const body = await response.json();
  const r = body.items.find(
    (r: { employeeId: string }) => r.employeeId === "E003",
  );
  expect(
    r,
    "Approved resignation fixture from resignations.spec.ts required",
  ).toBeDefined();
  return r.id as string;
}
const due = () => {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + 7);
  return d.toISOString().slice(0, 10);
};
test("HR assigns exit task with preserved errors; assignee completes on mobile without private resignation access", async ({
  page,
}) => {
  const title = `Synthetic equipment handover ${crypto.randomUUID().slice(0, 8)}`;
  await login(page, "amelia", "Resignations");
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
  const modal = page.getByRole("dialog"),
    region = page.getByRole("region", { name: "Case exit tasks", exact: true });
  await region
    .getByRole("button", { name: "Assign exit task", exact: true })
    .click();
  await region
    .getByRole("button", { name: "Confirm task assignment", exact: true })
    .click();
  await expect(region.getByRole("alert")).toBeFocused();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await region
    .getByLabel("Assigned employee ID (required)", { exact: true })
    .fill("E002");
  await region.getByLabel("Task title (required)", { exact: true }).fill(title);
  await region
    .getByLabel("Task instructions (required)", { exact: true })
    .fill("Record the synthetic equipment handover reference.");
  await region
    .getByLabel("Task due date (required)", { exact: true })
    .fill(due());
  await region
    .getByLabel("Assignment reason (required)", { exact: true })
    .fill("Coordinate this approved exit");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(modal).toBeVisible();
  await expect(
    region.getByLabel("Task title (required)", { exact: true }),
  ).toHaveValue(title);
  await region
    .getByRole("button", { name: "Confirm task assignment", exact: true })
    .click();
  await expect(
    region.getByRole("article", { name: `Exit task ${title}`, exact: true }),
  ).toBeVisible();
  await expect(region).toContainText("The exit handoff remains pending.");
  await region.screenshot({
    path: "docs/evidence/exit-coordination-desktop.png",
  });
  await modal.getByRole("button", { name: "Close panel", exact: true }).click();
  await logout(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "marcus", "My exit tasks");
  const own = page.getByRole("region", { name: "My exit tasks", exact: true }),
    card = own.getByRole("article", {
      name: `Exit task ${title}`,
      exact: true,
    });
  await expect(card).toBeVisible();
  await card
    .getByRole("button", { name: `Complete task ${title}`, exact: true })
    .click();
  await expect(modal).toContainText("does not execute payroll");
  await modal
    .getByRole("button", {
      name: "Confirm manual task completion",
      exact: true,
    })
    .click();
  await expect(modal.getByRole("alert")).toBeFocused();
  await modal
    .getByLabel("Completion comment (required)", { exact: true })
    .fill("Synthetic equipment receipt DEMO-482 recorded");
  await modal
    .getByRole("button", {
      name: "Confirm manual task completion",
      exact: true,
    })
    .click();
  await expect(modal).toHaveCount(0);
  await expect(card).toContainText("COMPLETED");
  await card.getByText(`Task audit for ${title}`, { exact: true }).click();
  await expect(card).toContainText("OPEN → COMPLETED");
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
  await card.screenshot({ path: "docs/evidence/exit-task-mobile.png" });
  await own.getByLabel("Exit task status").selectOption("OPEN");
  await expect(
    own.getByRole("article", { name: `Exit task ${title}`, exact: true }),
  ).toHaveCount(0);
});
test("task APIs enforce private scope, live contracts, stable replay and concurrent one-winner assignment/completion", async () => {
  const hr = await identity("amelia"),
    otherHr = await identity("olivia"),
    assignee = await identity("marcus"),
    secondSession = await identity("marcus"),
    employee = await identity("sophia"),
    outsider = await identity("outsider");
  try {
    const id = await caseId(hr),
      path = `/api/v1/resignations/${id}/tasks`;
    const snapshot = (await checkResponse(
      await hr.c.get(path),
      "/api/v1/resignations/{id}/tasks",
    )) as { version: number };
    const command = {
      assigneeId: "E002",
      title: "Synthetic API exit task",
      instructions: "Record a synthetic reference",
      dueDate: due(),
      reason: "Coordinate approved case",
      version: snapshot.version,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const version of [null, 0.5, -1])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...command, version },
          })
        ).status(),
      ).toBe(400);
    for (const user of [assignee, employee, outsider]) {
      expect(
        (
          await user.c.get(path, {
            headers: { "X-Tenant-ID": "atlas", "X-Role": "HR" },
          })
        ).status(),
      ).toBe(403);
      expect(
        (
          await user.c.post(path, { headers: user.headers, data: command })
        ).status(),
      ).toBe(403);
    }
    const responses = await Promise.all([
      hr.c.post(path, { headers: hr.headers, data: command }),
      otherHr.c.post(path, {
        headers: otherHr.headers,
        data: { ...command, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect(responses.map((r) => r.status()).sort()).toEqual([200, 409]);
    const winner = responses.find((r) => r.status() === 200)!;
    const result = (await checkResponse(
      winner,
      "/api/v1/resignations/{id}/tasks",
      "post",
    )) as { task: { id: string; version: number } };
    if (responses[0].status() === 200)
      expect(
        (
          await (
            await hr.c.post(path, { headers: hr.headers, data: command })
          ).json()
        ).task.id,
      ).toBe(result.task.id);
    const task = result.task,
      history = `/api/v1/exit-tasks/${task.id}/history`,
      complete = `/api/v1/exit-tasks/${task.id}/complete`;
    for (const user of [employee, outsider])
      expect((await user.c.get(history)).status()).toBe(403);
    expect(
      (await assignee.c.get(`/api/v1/resignations/${id}/history`)).status(),
    ).toBe(403);
    const completion = {
      comment: "API attestation only",
      version: 0,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await hr.c.post(complete, { headers: hr.headers, data: completion })
      ).status(),
    ).toBe(403);
    expect(
      (
        await assignee.c.post(complete, {
          headers: assignee.headers,
          data: { ...completion, version: 0.5 },
        })
      ).status(),
    ).toBe(400);
    const decisions = await Promise.all([
      assignee.c.post(complete, {
        headers: assignee.headers,
        data: completion,
      }),
      secondSession.c.post(complete, {
        headers: secondSession.headers,
        data: { ...completion, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect(decisions.map((r) => r.status()).sort()).toEqual([200, 409]);
    await checkResponse(
      decisions.find((r) => r.status() === 200)!,
      "/api/v1/exit-tasks/{id}/complete",
      "post",
    );
    const audit = (await checkResponse(
      await assignee.c.get(history),
      "/api/v1/exit-tasks/{id}/history",
    )) as unknown[];
    expect(audit).toHaveLength(2);
    const own = (await checkResponse(
      await assignee.c.get("/api/v1/exit-tasks?size=1"),
      "/api/v1/exit-tasks",
    )) as { total: number; items: unknown[] };
    expect(own.total).toBeGreaterThanOrEqual(2);
    expect(own.items).toHaveLength(1);
    const forbidden = (await checkResponse(
      await outsider.c.get("/api/v1/exit-tasks"),
      "/api/v1/exit-tasks",
    )) as { total: number };
    expect(forbidden.total).toBe(0);
    expect(
      (await assignee.c.get("/api/v1/exit-tasks?status=FORGED")).status(),
    ).toBe(400);
    expect(
      (await (await hr.c.get("/api/v1/employees/E003")).json()).status,
    ).toBe("Active");
  } finally {
    await Promise.all(
      [hr, otherHr, assignee, secondSession, employee, outsider].map((x) =>
        x.c.dispose(),
      ),
    );
  }
});
