import { test, expect, request, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
const employee = "E-REHIRE-TEST",
  path = `/api/v1/employees/${employee}/rehire`,
  endpoint = "/api/v1/employees/{employee}/rehire";
test.beforeAll(() => {
  execFileSync(
    "docker",
    [
      "compose",
      "exec",
      "-T",
      "postgres",
      "psql",
      "-X",
      "-U",
      "atlas",
      "-d",
      "atlas",
      "-v",
      "ON_ERROR_STOP=1",
    ],
    {
      input: readFileSync("tests/fixtures/former-employee.sql"),
      stdio: ["pipe", "pipe", "pipe"],
      timeout: 15000,
    },
  );
});
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
function date(days: number) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
test("rehire API rejects unsafe input, role/tenant escalation, self-review and competing decisions without creating an episode", async () => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    rival = await identity("olivia"),
    employeeUser = await identity("sophia"),
    outsider = await identity("outsider");
  try {
    const ctx = (await checkResponse(await hr.c.get(path), endpoint)) as {
      employeeVersion: number;
      employmentStatus: string;
      episodes: unknown[];
    };
    expect(ctx.employmentStatus).toBe("Ended");
    expect(ctx.episodes).toHaveLength(1);
    const c = {
      startDate: date(10),
      title: "Returning analyst",
      department: "Engineering",
      location: "London",
      managerId: "E002",
      reason: "Synthetic rehire request",
      version: ctx.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const user of [employeeUser, outsider]) {
      expect((await user.c.get(path)).status()).toBe(403);
      expect(
        (
          await user.c.post(path, {
            headers: {
              ...user.headers,
              "X-Role": "HR",
              "X-Tenant-ID": "atlas",
            },
            data: c,
          })
        ).status(),
      ).toBe(403);
    }
    for (const version of [null, -1, 0.5])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...c, version },
          })
        ).status(),
      ).toBe(400);
    for (const startDate of [null, date(-1), date(500)])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...c, startDate },
          })
        ).status(),
      ).toBe(400);
    const r = (await checkResponse(
      await hr.c.post(path, { headers: hr.headers, data: c }),
      endpoint,
      "post",
    )) as { id: string };
    expect(
      (await (await hr.c.post(path, { headers: hr.headers, data: c })).json())
        .id,
    ).toBe(r.id);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...c, title: "Changed replay" },
        })
      ).status(),
    ).toBe(409);
    const reviewPath = `${path}/${r.id}/review`,
      review = {
        decision: "REJECTED",
        reason: "Independent rejection keeps prior episode",
        version: c.version,
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
      endpoint + "/{id}/review",
      "post",
    );
    expect((await (await hr.c.get(path)).json()).episodes).toHaveLength(1);
  } finally {
    await Promise.all(
      [hr, reviewer, rival, employeeUser, outsider].map((x) => x.c.dispose()),
    );
  }
});
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await page.getByLabel("Search employees").fill(employee);
  await page
    .getByRole("button", { name: "View Alex Morgan", exact: true })
    .click();
}
test("HR plans and independently approves rehire with retained episodes, preserved errors and explicit blocked legacy access", async ({
  page,
}) => {
  await login(page, "amelia");
  const panel = page.getByRole("region", {
    name: "Employment episodes and rehire",
    exact: true,
  });
  await panel.getByRole("button", { name: "Plan rehire", exact: true }).click();
  await panel
    .getByRole("button", { name: "Submit rehire request", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByLabel("Rehire start date (required)", { exact: true })
    .fill(date(10));
  await panel
    .getByLabel("Rehire job title (required)", { exact: true })
    .fill("Returning analyst");
  await panel
    .getByLabel("Find a department", { exact: true })
    .fill("Engineering");
  await panel
    .getByLabel("Rehire department (required)", { exact: true })
    .selectOption("Engineering");
  await panel
    .getByLabel("Rehire location (required)", { exact: true })
    .fill("London");
  await panel.locator("#rehire-managerId").selectOption("E002");
  await panel
    .getByLabel("Rehire reason (required)", { exact: true })
    .fill("Rehire into a new onboarding episode; keep old access blocked");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    panel.getByLabel("Rehire job title (required)", { exact: true }),
  ).toHaveValue("Returning analyst");
  await panel
    .getByRole("button", { name: "Submit rehire request", exact: true })
    .click();
  await expect(panel).toContainText("SUBMITTED");
  await expect(panel).toContainText("Another HR employee must review");
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await login(page, "olivia");
  await panel
    .getByRole("button", { name: "Review rehire", exact: true })
    .click();
  await expect(panel).toContainText("reset position assignment");
  await panel
    .getByLabel("Rehire reason (required)", { exact: true })
    .fill(
      "Independent HR confirms new onboarding episode; fresh access review required",
    );
  await panel
    .getByRole("button", { name: "Record rehire decision", exact: true })
    .click();
  await expect(panel).toContainText("Episode 2 · SCHEDULED");
  await expect(panel).toContainText(
    "All legacy identities remain locally blocked",
  );
  await expect(panel).toContainText("Reviewed by olivia");
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
  await panel.screenshot({ path: "docs/evidence/rehire-mobile.png" });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page.getByRole("button", { name: /Alex Morgan/ }).click();
  await expect(panel).toContainText(
    "Independent HR confirms new onboarding episode",
  );
});
