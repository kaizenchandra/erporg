import {
  test,
  expect,
  request,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { Sheet } from "../features/timekeeping/contracts";
import type { TimeReport } from "../features/timekeeping/reports";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
async function headers(c: APIRequestContext) {
  const csrf = await (await c.get("/api/csrf")).json();
  return { [csrf.headerName]: csrf.token };
}
async function client(user: string) {
  const c = await request.newContext({ baseURL: origin });
  expect(
    (
      await c.post("/api/login", {
        headers: await headers(c),
        form: { username: user, password: "atlas-demo" },
      })
    ).status(),
  ).toBe(204);
  return c;
}
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Time & attendance", exact: true })
    .click();
}
function week(n: number) {
  const d = new Date();
  d.setUTCHours(0, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7) - n * 7);
  return d.toISOString().slice(0, 10);
}
test("worker copies an owned week, reloads it, submits and manager reports approved allocated minutes", async ({
  page,
}) => {
  const worker = await client("sophia"),
    manager = await client("marcus");
  // Use a caller-selected pair of unused weeks; existing acceptance data is never deleted.
  const offset = Number(process.env.ATLAS_REPORT_WEEK_OFFSET ?? 100),
    source = week(offset),
    target = week(offset - 1);
  try {
    const entries = Array.from({ length: 7 }, (_, i) => {
      const d = new Date(`${source}T00:00:00Z`);
      d.setUTCDate(d.getUTCDate() + i);
      return {
        date: d.toISOString().slice(0, 10),
        minutes: i === 0 ? 90 : 0,
        code: i === 0 ? "SYNTHETIC-REPORT" : "",
      };
    });
    const r = await worker.post("/api/v1/time/sheets", {
      headers: await headers(worker),
      data: {
        week: source,
        zone: "Etc/UTC",
        version: 0,
        entries,
        reason: "Synthetic copy/report acceptance",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    const s = (await checkResponse(r, "/api/v1/time/sheets", "post")) as Sheet;
    await signIn(page, "sophia");
    await page
      .getByRole("button", { name: "My timesheets", exact: true })
      .click();
    // Wait for each asynchronous page before deciding whether to advance.
    await expect(
      page.getByText("Loading timesheets…", { exact: true }),
    ).toBeHidden();
    while (
      !(await page
        .getByRole("button", {
          name: `Open timesheet for ${source} · E003`,
          exact: true,
        })
        .count())
    ) {
      await page
        .getByRole("button", { name: "Next page", exact: true })
        .click();
      await expect(
        page.getByText("Loading timesheets…", { exact: true }),
      ).toBeHidden();
    }
    await page
      .getByRole("button", {
        name: `Open timesheet for ${source} · E003`,
        exact: true,
      })
      .click();
    const dialog = page.getByRole("dialog");
    await dialog
      .getByText("Copy this week to a new draft", { exact: true })
      .click();
    await dialog.getByLabel("Copy to reporting Monday").fill(target);
    await dialog
      .getByLabel("Copy reason")
      .fill("Reviewed actual copied working time");
    page.once("dialog", (dialog) => dialog.dismiss());
    await dialog
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
    await expect(dialog.getByLabel("Copy reason")).toHaveValue(
      "Reviewed actual copied working time",
    );
    await dialog
      .getByRole("checkbox", {
        name: "I will review copied hours against actual work before submitting.",
      })
      .check();
    await dialog
      .getByRole("button", { name: "Create copied draft", exact: true })
      .click();
    await expect(dialog).not.toBeVisible();
    await page.reload();
    await page
      .getByRole("button", { name: "Time & attendance", exact: true })
      .click();
    await page
      .getByRole("button", { name: "My timesheets", exact: true })
      .click();
    await expect(
      page.getByText("Loading timesheets…", { exact: true }),
    ).toBeHidden();
    while (
      !(await page
        .getByRole("button", {
          name: `Open timesheet for ${target} · E003`,
          exact: true,
        })
        .count())
    ) {
      await page
        .getByRole("button", { name: "Next page", exact: true })
        .click();
      await expect(
        page.getByText("Loading timesheets…", { exact: true }),
      ).toBeHidden();
    }
    await page
      .getByRole("button", {
        name: `Open timesheet for ${target} · E003`,
        exact: true,
      })
      .click();
    await expect(
      dialog.getByText("Working time:", { exact: false }),
    ).toContainText("1h 30m");
    await dialog
      .getByLabel("Timesheet comment")
      .fill("I reviewed the copied actual work");
    await dialog
      .getByRole("checkbox", {
        name: "I have checked this timesheet and its impact.",
      })
      .check();
    await dialog
      .getByRole("button", {
        name: "Submit timesheet for approval",
        exact: true,
      })
      .click();
    await expect(dialog).not.toBeVisible();
    const list = (await (
      await worker.get("/api/v1/time/reports", {
        params: {
          from: target,
          through: target,
          employeeId: "E003",
          status: "SUBMITTED",
        },
      })
    ).json()) as TimeReport;
    expect(list.items).toHaveLength(1);
    const approved = await manager.post(
      `/api/v1/time/sheets/${list.items[0].id}/actions`,
      {
        headers: await headers(manager),
        data: {
          version: list.items[0].version,
          action: "APPROVE",
          comment: "Verified copied actual hours",
          idempotencyKey: crypto.randomUUID(),
        },
      },
    );
    expect(approved.status()).toBe(200);
    await page
      .getByRole("button", { name: "Time reports", exact: true })
      .click();
    await page.getByLabel("First reporting Monday").fill(target);
    await page.getByLabel("Last reporting Monday").fill(target);
    await page.getByLabel("Worker ID (optional)").fill("E003");
    await page.getByLabel("Report status").selectOption("APPROVED");
    await page.getByRole("button", { name: "Apply report filters" }).click();
    await expect(
      page.getByRole("status").filter({ hasText: "1 timesheets" }),
    ).toContainText("Posted hours 1h 30m");
    const downloadPromise = page.waitForEvent("download");
    await page
      .getByRole("button", { name: "Export all filtered timesheets (CSV)" })
      .click();
    const download = await downloadPromise;
    expect(download.suggestedFilename()).toBe("atlas-time-report.csv");
    await expect(
      page.getByRole("status").filter({ hasText: "Exported 1 authorised" }),
    ).toBeVisible();
    const csv = await manager.post("/api/v1/time/reports/export", {
      headers: await headers(manager),
      data: {
        from: target,
        through: target,
        employeeId: "E003",
        status: "APPROVED",
      },
    });
    const body = (await checkResponse(
      csv,
      "/api/v1/time/reports/export",
      "post",
    )) as { content: string };
    expect(body.content).toContain(",90,90\r\n");
    const forbidden = await manager.post("/api/v1/time/sheets/copy", {
      headers: await headers(manager),
      data: {
        sourceId: s.id,
        sourceVersion: 0,
        week: target,
        reason: "Must fail",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(forbidden.status()).toBe(403);
    await page.setViewportSize({ width: 390, height: 844 });
    const a11y = await new AxeBuilder({ page })
      .include('[aria-label="Time reports"]')
      .analyze();
    expect(
      a11y.violations.filter((v) =>
        ["serious", "critical"].includes(v.impact ?? ""),
      ),
    ).toEqual([]);
    await page.screenshot({
      path: "docs/evidence/timesheet-reports-mobile.png",
      fullPage: true,
    });
  } finally {
    await worker.dispose();
    await manager.dispose();
  }
});
test("report contract rejects unauthorised workers and invalid ranges; export requires CSRF", async () => {
  const worker = await client("sophia"),
    outsider = await client("outsider");
  try {
    const q = {
      from: week(4),
      through: week(1),
      employeeId: "E003",
      status: "ALL",
    };
    expect(
      (await outsider.get("/api/v1/time/reports", { params: q })).status(),
    ).toBe(403);
    expect(
      (
        await worker.get("/api/v1/time/reports", {
          params: { ...q, employeeId: "E001" },
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await worker.get("/api/v1/time/reports", {
          params: { ...q, through: week(5) },
        })
      ).status(),
    ).toBe(400);
    expect(
      (await worker.post("/api/v1/time/reports/export", { data: q })).status(),
    ).toBe(403);
    await checkResponse(
      await worker.get("/api/v1/time/reports", { params: q }),
      "/api/v1/time/reports",
    );
  } finally {
    await worker.dispose();
    await outsider.dispose();
  }
});
test("weekly dashboard reflects persisted own status and current scoped approval count", async ({
  page,
}) => {
  const worker = await client("sophia"),
    manager = await client("marcus");
  const w = week(0);
  try {
    const own = (await checkResponse(
      await worker.get("/api/v1/time/dashboard", { params: { week: w } }),
      "/api/v1/time/dashboard",
    )) as { sheet: Sheet | null; pendingApprovals: number; canReview: boolean };
    expect(own.canReview).toBe(false);
    expect(own.pendingApprovals).toBe(0);
    await signIn(page, "sophia");
    await page.getByRole("button", { name: "My week", exact: true }).click();
    const dashboard = page.getByRole("region", {
      name: "Working time dashboard",
    });
    if (own.sheet)
      await expect(
        dashboard.getByText(`recorded · Revision ${own.sheet.version}`, {
          exact: false,
        }),
      ).toBeVisible();
    else
      await expect(
        dashboard.getByText(
          "No timesheet recorded for this week and current employment episode.",
        ),
      ).toBeVisible();
    await expect(
      dashboard.getByRole("heading", { name: "Pending team approvals" }),
    ).toHaveCount(0);
    await expect(
      dashboard.getByText("Missing expected hours cannot yet be calculated.", {
        exact: false,
      }),
    ).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    const accessibility = await new AxeBuilder({ page })
      .include('[aria-label="Working time dashboard"]')
      .analyze();
    expect(
      accessibility.violations.filter((v) =>
        ["critical", "serious"].includes(v.impact ?? ""),
      ),
    ).toEqual([]);
    await page.screenshot({
      path: "docs/evidence/timesheet-dashboard-mobile.png",
      fullPage: true,
    });
    const team = (await checkResponse(
      await manager.get("/api/v1/time/dashboard", { params: { week: w } }),
      "/api/v1/time/dashboard",
    )) as { canReview: boolean; pendingApprovals: number };
    expect(team.canReview).toBe(true);
    const submitted = (await (
      await manager.get("/api/v1/time/sheets", {
        params: { review: "true", status: "SUBMITTED", size: "1" },
      })
    ).json()) as { total: number };
    expect(team.pendingApprovals).toBe(submitted.total);
  } finally {
    await worker.dispose();
    await manager.dispose();
  }
});
