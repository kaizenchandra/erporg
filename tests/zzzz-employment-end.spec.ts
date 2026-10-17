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
  const token = await (await c.get("/api/csrf")).json();
  return { c, headers: { [token.headerName]: token.token } };
}
async function login(page: Page) {
  await page.goto("/");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Resignations", exact: true }).click();
}
async function approvedId(
  hr: Awaited<ReturnType<typeof identity>>,
  employeeId: string,
) {
  const list = await (
    await hr.c.get("/api/v1/resignations?status=APPROVED&size=100")
  ).json();
  const r = list.items.find(
    (r: { employeeId: string }) => r.employeeId === employeeId,
  );
  expect(r, "Approved resignation fixture required").toBeDefined();
  return r.id as string;
}
test("HR confirms future employment end with impact, retained validation, unsaved recovery and pending handoffs", async ({
  page,
}) => {
  await login(page);
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
  const modal = page.getByRole("dialog"),
    panel = page.getByRole("region", { name: "Employment end", exact: true });
  await panel
    .getByRole("button", { name: "Review employment end", exact: true })
    .click();
  await expect(panel).toContainText("every mapped identity");
  await panel
    .getByRole("button", {
      name: "Confirm employment end and local cutoff",
      exact: true,
    })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await panel
    .getByLabel("Employment end confirmation reason (required)", {
      exact: true,
    })
    .fill("Confirm agreed synthetic employment end; final pay remains pending");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(modal).toBeVisible();
  await expect(
    panel.getByLabel("Employment end confirmation reason (required)", {
      exact: true,
    }),
  ).toHaveValue(
    "Confirm agreed synthetic employment end; final pay remains pending",
  );
  await panel
    .getByRole("button", {
      name: "Confirm employment end and local cutoff",
      exact: true,
    })
    .click();
  await expect(panel).toContainText("Scheduled");
  await expect(panel).toContainText(
    "Existing sessions are invalidated on their next request",
  );
  await expect(panel).toContainText("handoffs remain pending");
  await expect(
    page.getByRole("button", { name: "Assign exit task", exact: true }),
  ).toHaveCount(0);
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
  await panel.screenshot({ path: "docs/evidence/employment-end-mobile.png" });
  await modal.getByRole("button", { name: "Close panel", exact: true }).click();
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
  await expect(panel).toContainText("Confirm agreed synthetic employment end");
});
test("API denies unsafe schedules and concurrent confirmations commit one end; current access remains valid before cutoff", async () => {
  const hr = await identity("amelia"),
    other = await identity("amelia"),
    employee = await identity("sophia"),
    owner = await identity("olivia"),
    outsider = await identity("outsider");
  try {
    const id = await approvedId(hr, "E003"),
      path = `/api/v1/resignations/${id}/employment-end`;
    const scheduled = (await checkResponse(
      await hr.c.get(path),
      "/api/v1/resignations/{id}/employment-end",
    )) as { record: { inactiveDate: string }; phase: string };
    expect(scheduled.phase).toBe("SCHEDULED");
    for (const c of [employee, outsider]) {
      expect((await c.c.get(path)).status()).toBe(403);
      expect(
        (
          await c.c.post(path, {
            headers: { ...c.headers, "X-Role": "HR", "X-Tenant-ID": "atlas" },
            data: {
              reason: "Forbidden",
              version: 0,
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(403);
    }
    const profile = await (await owner.c.get("/api/v1/employees/E005")).json();
    const d = new Date();
    d.setUTCDate(d.getUTCDate() + 40);
    const submitted = await owner.c.post("/api/v1/resignations", {
      headers: owner.headers,
      data: {
        lastWorkingDate: d.toISOString().slice(0, 10),
        reason: "Synthetic end concurrency",
        employeeVersion: profile.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(submitted.status()).toBe(200);
    const r = await submitted.json();
    expect(
      (
        await hr.c.post(`/api/v1/resignations/${r.id}/decision`, {
          headers: hr.headers,
          data: {
            decision: "APPROVED",
            comment: "Independent review",
            version: 0,
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    const endPath = `/api/v1/resignations/${r.id}/employment-end`,
      ctx = await (await hr.c.get(endPath)).json(),
      command = {
        reason: "Independent end confirmation",
        version: ctx.employeeVersion,
        idempotencyKey: crypto.randomUUID(),
      };
    for (const version of [null, 0.5, -1])
      expect(
        (
          await hr.c.post(endPath, {
            headers: hr.headers,
            data: { ...command, version },
          })
        ).status(),
      ).toBe(400);
    expect(
      (
        await owner.c.post(endPath, { headers: owner.headers, data: command })
      ).status(),
    ).toBe(403);
    const rival = { ...command, idempotencyKey: crypto.randomUUID() };
    const responses = await Promise.all([
      hr.c.post(endPath, { headers: hr.headers, data: command }),
      other.c.post(endPath, { headers: other.headers, data: rival }),
    ]);
    expect(responses.map((r) => r.status()).sort()).toEqual([200, 409]);
    const winning = responses[0].status() === 200 ? command : rival;
    await checkResponse(
      responses.find((r) => r.status() === 200)!,
      "/api/v1/resignations/{id}/employment-end",
      "post",
    );
    expect(
      (
        await hr.c.post(endPath, { headers: hr.headers, data: winning })
      ).status(),
    ).toBe(200);
    expect(
      (
        await hr.c.post(endPath, {
          headers: hr.headers,
          data: { ...winning, reason: "Changed replay" },
        })
      ).status(),
    ).toBe(409);
    const tasks = await (
      await hr.c.get(`/api/v1/resignations/${r.id}/tasks`)
    ).json();
    expect(
      (
        await hr.c.post(`/api/v1/resignations/${r.id}/tasks`, {
          headers: hr.headers,
          data: {
            assigneeId: "E002",
            title: "Too late",
            instructions: "No post-schedule tasks",
            dueDate: d.toISOString().slice(0, 10),
            reason: "Invalid coordination",
            version: tasks.version,
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(409);
    expect((await employee.c.get("/api/v1/session")).status()).toBe(200);
    expect(
      (await (await hr.c.get("/api/v1/employees/E003")).json()).status,
    ).toBe("Active");
  } finally {
    await Promise.all(
      [hr, other, employee, owner, outsider].map((c) => c.c.dispose()),
    );
  }
});
