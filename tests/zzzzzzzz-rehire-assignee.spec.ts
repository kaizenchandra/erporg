import { test, expect, request, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
const returning = "E-RETURN-TEST",
  departing = "E-ASSIGNEE-CASE";
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
async function login(page: Page, user: string, tab: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: tab, exact: true }).click();
}
test.beforeAll(() => {
  // Explicit historical approved colleague case, with no employment end.
  // Returning employee's real rehire/activation runs in the preceding activation suite.
  const fixture =
    readFileSync("tests/fixtures/former-employee.sql", "utf8")
      .split("insert into hr_employment_end(")[0]
      .replaceAll("E-REHIRE-TEST", departing)
      .replaceAll("rehire-former-", "assignee-case-")
      .replaceAll("former-", "assignee-case-legacy-")
      .replaceAll("Alex Morgan", "Taylor Quinn")
      .replaceAll("alex.rehire@example.test", "taylor.assignee@example.test") +
    "commit;\n";
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
    { input: fixture, stdio: ["pipe", "pipe", "pipe"], timeout: 15000 },
  );
});
test("HR assigns and reassigns colleague tasks to a freshly authorised rehire; Employee completes on mobile while old exit case stays closed", async ({
  page,
}) => {
  const hr = await identity("amelia"),
    oldAssignee = await identity("marcus");
  try {
    const p = await (await hr.c.get(`/api/v1/employees/${returning}`)).json();
    expect(p.status).toBe("Active");
    const rows = (
      await (
        await hr.c.get("/api/v1/resignations?status=APPROVED&size=100")
      ).json()
    ).items as { id: string; employeeId: string }[];
    const colleague = rows.find((r) => r.employeeId === departing)!;
    const prior = rows.find((r) => r.employeeId === returning)!;
    expect(colleague).toBeTruthy();
    expect(prior).toBeTruthy();
    const due = new Date();
    due.setUTCDate(due.getUTCDate() + 10);
    const day = due.toISOString().slice(0, 10),
      title = "Handover to returning colleague";
    await login(page, "amelia", "Resignations");
    await page
      .getByRole("button", {
        name: `Review resignation ${departing}`,
        exact: true,
      })
      .click();
    const region = page.getByRole("region", {
      name: "Case exit tasks",
      exact: true,
    });
    await region
      .getByRole("button", { name: "Assign exit task", exact: true })
      .click();
    await region
      .getByLabel("Assigned employee ID (required)", { exact: true })
      .fill(returning);
    await region
      .getByLabel("Task title (required)", { exact: true })
      .fill(title);
    await region
      .getByLabel("Task instructions (required)", { exact: true })
      .fill("Record the synthetic handover receipt");
    await region
      .getByLabel("Task due date (required)", { exact: true })
      .fill(day);
    await region
      .getByLabel("Assignment reason (required)", { exact: true })
      .fill("Use current returning Employee access");
    const created = page.waitForResponse(
      (r) =>
        new URL(r.url()).pathname ===
          `/api/v1/resignations/${colleague.id}/tasks` &&
        r.request().method() === "POST",
    );
    await region
      .getByRole("button", { name: "Confirm task assignment", exact: true })
      .click();
    await checkResponse(
      await created,
      "/api/v1/resignations/{id}/tasks",
      "post",
    );
    await expect(
      region.getByRole("article", { name: `Exit task ${title}`, exact: true }),
    ).toBeVisible();
    const command = {
      assigneeId: "E002",
      title: "Reassigned handover",
      instructions: "Record second synthetic receipt",
      dueDate: day,
      reason: "Initial colleague assignment",
      version: 1,
      idempotencyKey: crypto.randomUUID(),
    };
    const response = await hr.c.post(
      `/api/v1/resignations/${colleague.id}/tasks`,
      { headers: hr.headers, data: command },
    );
    expect(response.status()).toBe(200);
    const second = (await response.json()).task;
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
    await page
      .getByRole("button", {
        name: `Review resignation ${departing}`,
        exact: true,
      })
      .click();
    await region
      .getByRole("button", {
        name: "Manage task Reassigned handover",
        exact: true,
      })
      .click();
    const form = page.getByRole("form", {
      name: "Manage exit task",
      exact: true,
    });
    await form
      .getByLabel("New assigned employee ID (required)", { exact: true })
      .fill(returning);
    await form
      .getByLabel("Task management reason (required)", { exact: true })
      .fill("Private HR reassignment context");
    await form
      .getByRole("button", { name: "Confirm task reassignment", exact: true })
      .click();
    await expect(form).toHaveCount(0);
    expect(
      (
        await oldAssignee.c.get(`/api/v1/exit-tasks/${second.id}/history`)
      ).status(),
    ).toBe(403);
    expect(
      (
        await hr.c.post(`/api/v1/resignations/${prior.id}/tasks`, {
          headers: hr.headers,
          data: { ...command, version: 0, idempotencyKey: crypto.randomUUID() },
        })
      ).status(),
    ).toBe(409);
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page, "returning", "My exit tasks");
    const own = page.getByRole("region", {
      name: "My exit tasks",
      exact: true,
    });
    await expect(own).not.toContainText("Private HR reassignment context");
    for (const name of [title, "Reassigned handover"]) {
      const card = own.getByRole("article", {
        name: `Exit task ${name}`,
        exact: true,
      });
      await card
        .getByRole("button", { name: `Complete task ${name}`, exact: true })
        .click();
      const modal = page.getByRole("dialog");
      await modal
        .getByLabel("Completion comment (required)", { exact: true })
        .fill("Receipt recorded by current Employee identity");
      await modal
        .getByRole("button", {
          name: "Confirm manual task completion",
          exact: true,
        })
        .click();
      await expect(modal).toHaveCount(0);
      await expect(card).toContainText("COMPLETED");
    }
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
    await page.screenshot({ path: "docs/evidence/rehire-assignee-mobile.png" });
  } finally {
    await hr.c.dispose();
    await oldAssignee.c.dispose();
  }
});
