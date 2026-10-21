import {
  test,
  expect,
  request,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { Run } from "../features/payroll/contracts";
import type { Compensation } from "../features/talent/contracts";
import type { Salary } from "../features/talent/compensation-source";
import { writeFileSync } from "node:fs";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
test.setTimeout(120000);
async function headers(c: APIRequestContext) {
  const t = await (await c.get("/api/csrf")).json();
  return { [t.headerName]: t.token };
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
async function post<T>(
  c: APIRequestContext,
  path: string,
  data: Record<string, unknown>,
) {
  const r = await c.post(path, {
    headers: await headers(c),
    data: { ...data, idempotencyKey: crypto.randomUUID() },
  });
  expect(r.status(), await r.text()).toBe(200);
  return (await r.json()) as T;
}
async function signIn(p: Page, user: string, view = "Talent") {
  await p.goto(origin);
  await p.getByLabel("Workspace account").selectOption(user);
  await p.getByRole("button", { name: "Enter workspace", exact: true }).click();
  await p.getByRole("button", { name: view, exact: true }).click();
  p.on("dialog", (d) => d.accept());
}
async function action(p: Page, value: string) {
  const d = p.getByRole("dialog");
  await d.getByLabel("Payroll action", { exact: false }).selectOption(value);
  await d
    .getByLabel("Payroll decision reason")
    .fill("Independent synthetic " + value);
  await d
    .getByRole("checkbox", {
      name: "I checked the employee, source snapshot, amounts, exceptions and action impact.",
    })
    .check();
  await d
    .getByRole("button", { name: "Confirm payroll action", exact: true })
    .click();
  await expect(d).toBeHidden();
}
test("authoritative salary and bonus produce explained payroll, released payslip, balanced finance and scoped exports", async ({
  browser,
  page,
}) => {
  const hr = await client("amelia"),
    reviewer = await client("olivia"),
    worker = await client("marcus");
  const a = await browser.newContext(),
    review = await a.newPage();
  try {
    const today = new Date().toISOString().slice(0, 10);
    const entity = "DEMO-FIN";
    let run: Run, planId: string, sheetId: string;
    if (process.env.TALENT_RESUME_COMPENSATION === "1") {
      const rows = (await (
        await hr.get(`/api/v1/payroll/runs?entity=${entity}`)
      ).json()) as { items: Run[] };
      const prior = rows.items.find(
        (r) =>
          r.employeeId === "E002" &&
          r.ruleVersion.startsWith("DEMO-COMPENSATION"),
      );
      expect(prior).toBeDefined();
      run = prior!;
      expect(run.status).toBe("CALCULATED");
      const calculation = (await (
        await hr.get(`/api/v1/payroll/compensation/calculations/${run.id}`)
      ).json()) as { bonuses: { sourceId: string }[] };
      planId = calculation.bonuses[0].sourceId;
      sheetId = run.source.id;
      expect(run.gross).toBe("556.17");
      expect(run.net).toBe("555.71");
      await signIn(page, "amelia", "Payroll");
    } else {
      const employee = (await (
        await hr.get("/api/v1/employees/E002")
      ).json()) as { version: number; legalEntityCode: string };
      if (employee.legalEntityCode !== entity) {
        const r = await post<{ id: string }>(hr, "/api/v1/requests", {
          employeeId: "E002",
          field: "legalEntityCode",
          newValue: entity,
          effectiveDate: today,
          version: employee.version,
          reason: "Synthetic compensation payroll entity",
        });
        await post(reviewer, `/api/v1/requests/${r.id}/decision`, {
          decision: "APPROVED",
          comment: "Independent entity review",
        });
      }
      await signIn(page, "amelia");
      await page
        .getByRole("button", { name: "Compensation", exact: true })
        .click();
      await page
        .getByRole("button", { name: "Create compensation", exact: true })
        .click();
      await page
        .getByRole("combobox", { name: "Compensation worker", exact: true })
        .selectOption("E002");
      const source = page
        .getByRole("heading", { name: "Authoritative payroll compensation" })
        .locator("..");
      await source
        .getByLabel("Verified annual salary", { exact: false })
        .fill("100000.00");
      await source
        .getByLabel("Approved annual paid-hours basis")
        .fill("2000.00");
      await source.getByLabel("Baseline effective date").fill(today);
      await source
        .getByLabel("Reason for this action")
        .fill("Approved synthetic annual salary and explicit hours basis");
      const [baselineResponse] = await Promise.all([
        page.waitForResponse(
          (r) =>
            r.url().endsWith("/compensation/baselines") &&
            r.request().method() === "POST",
        ),
        source
          .getByRole("button", { name: "Submit payroll compensation baseline" })
          .click(),
      ]);
      expect(baselineResponse.status(), await baselineResponse.text()).toBe(
        200,
      );
      await checkResponse(
        baselineResponse,
        "/api/v1/payroll/compensation/baselines",
        "post",
      );
      const baseline = (await baselineResponse.json()) as { id: string };
      await post(
        reviewer,
        `/api/v1/payroll/compensation/baselines/${baseline.id}/decisions`,
        {
          action: "APPROVE",
          reason: "Independent verification of compensation source",
        },
      );
      const salaryResponse = await hr.get(
        `/api/v1/payroll/compensation/employees/E002/salary?date=${today}`,
      );
      await checkResponse(
        salaryResponse,
        "/api/v1/payroll/compensation/employees/{employee}/salary",
      );
      expect(((await salaryResponse.json()) as Salary).annualSalary).toBe(
        "100000.00",
      );
      const planner = page.locator("form").filter({
        has: page.getByRole("button", {
          name: "Save compensation plan",
          exact: true,
        }),
      });
      await planner
        .getByLabel("Compensation plan name")
        .fill("Authoritative browser award");
      await planner.getByLabel("Planning budget").fill("10500.25");
      await planner.getByLabel("Worker", { exact: false }).selectOption("E002");
      await planner.getByLabel("Effective date").fill(today);
      await expect(
        planner.getByText(/Authoritative current salary/),
      ).toContainText("100,000.00");
      await planner.getByLabel("Proposed annual salary").fill("110000.00");
      await planner.getByLabel("One-time bonus").fill("500.25");
      await planner
        .getByLabel("Reason for this action")
        .fill("Independent synthetic salary increase and bonus");
      const [planResponse] = await Promise.all([
        page.waitForResponse(
          (r) =>
            r.url().endsWith("/talent/compensation") &&
            r.request().method() === "POST",
        ),
        planner.getByRole("button", { name: "Save compensation plan" }).click(),
      ]);
      expect(planResponse.status(), await planResponse.text()).toBe(200);
      let plan = (await planResponse.json()) as Compensation;
      plan = await post<Compensation>(
        hr,
        `/api/v1/talent/compensation/${plan.meta.id}/actions`,
        {
          version: 0,
          action: "SUBMIT",
          reason: "Review authoritative proposal",
        },
      );
      await post(
        reviewer,
        `/api/v1/talent/compensation/${plan.meta.id}/actions`,
        {
          version: plan.meta.version,
          action: "APPROVE",
          reason: "Independent salary and bonus approval",
        },
      );
      const monday = new Date(`${today}T00:00:00Z`);
      monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
      const week = monday.toISOString().slice(0, 10);
      const entries = Array.from({ length: 7 }, (_, i) => {
        const d = new Date(monday);
        d.setUTCDate(d.getUTCDate() + i);
        const date = d.toISOString().slice(0, 10);
        return {
          date,
          minutes: date === today ? 61 : 0,
          code: date === today ? "SYNTHETIC-ENGINEERING" : "",
        };
      });
      const sheet = await post<{ id: string }>(worker, "/api/v1/time/sheets", {
        week,
        zone: "Etc/UTC",
        version: 0,
        entries,
        reason: "Synthetic approved source for authoritative compensation",
      });
      await post(worker, `/api/v1/time/sheets/${sheet.id}/actions`, {
        version: 0,
        action: "SUBMIT",
        comment: "Actual synthetic time",
      });
      await post(hr, `/api/v1/time/sheets/${sheet.id}/actions`, {
        version: 1,
        action: "APPROVE",
        comment: "Independent synthetic time review",
      });
      // Close the inspected talent record before navigating; no unsaved form state is discarded.
      await page.getByRole("button", { name: "Payroll", exact: true }).click();
      await page
        .getByRole("button", { name: "Calculate payroll", exact: true })
        .click();
      const d = page.getByRole("dialog");
      await expect(d.getByLabel("Calculation source")).toHaveValue(
        "COMPENSATION",
      );
      await d.getByLabel("Approved timesheet ID").fill(sheet.id);
      await d.getByLabel("Voluntary deduction (USD)").fill("0.46");
      await d
        .getByLabel("Payroll calculation reason")
        .fill("Consume approved effective salary and once-only bonus");
      const [runResponse] = await Promise.all([
        page.waitForResponse(
          (r) =>
            r.url().endsWith("/payroll/compensation-runs") &&
            r.request().method() === "POST",
        ),
        d
          .getByRole("button", { name: "Calculate demonstration result" })
          .click(),
      ]);
      expect(runResponse.status(), await runResponse.text()).toBe(200);
      await checkResponse(
        runResponse,
        "/api/v1/payroll/compensation-runs",
        "post",
      );
      run = (await runResponse.json()) as Run;
      planId = plan.meta.id;
      sheetId = sheet.id;
      expect(run.gross).toBe("556.17");
      expect(run.net).toBe("555.71");
      await expect(d).toBeHidden();
    }
    await page
      .getByRole("button", {
        name: `View payroll result ${run.id}`,
        exact: true,
      })
      .click();
    const snapshot = page.getByRole("region", {
      name: "Immutable compensation calculation",
    });
    await expect(snapshot).toContainText("500.25");
    await expect(snapshot).toContainText("55.92");
    expect(
      (await new AxeBuilder({ page }).include("dialog").analyze()).violations,
    ).toEqual([]);
    await action(page, "SUBMIT");
    await signIn(review, "olivia", "Payroll");
    await review
      .getByRole("button", {
        name: `View payroll result ${run.id}`,
        exact: true,
      })
      .click();
    await action(review, "FINALIZE");
    const forbidden = await client("sophia");
    expect(
      (
        await forbidden.get(
          `/api/v1/payroll/compensation/calculations/${run.id}`,
        )
      ).status(),
    ).toBe(403);
    await forbidden.dispose();
    const final = (await (
      await hr.get(`/api/v1/payroll/runs/${run.id}`)
    ).json()) as Run;
    expect(final.status).toBe("FINALIZED");
    const payslip = await post<{ filename: string; content: string }>(
      worker,
      `/api/v1/payroll/runs/${run.id}/payslip`,
      {},
    );
    expect(payslip.content).toContain("Net 555.71");
    expect(payslip.content).toContain(run.id);
    const linked = await post<Run>(
      hr,
      `/api/v1/payroll/runs/${run.id}/actions`,
      {
        version: final.version,
        action: "CREATE_JOURNAL",
        reason: "Reconcile exact source totals to finance",
      },
    );
    const journal = (await (
      await hr.get(`/api/v1/finance/journals/${linked.journalId}`)
    ).json()) as {
      id: string;
      status: string;
      version: number;
      lines: { debit: string; credit: string }[];
    };
    expect(journal.lines[0].debit).toBe("556.17");
    const minor = (v: string) => BigInt(v.replace(".", ""));
    expect(journal.lines.reduce((a, l) => a + minor(l.debit), BigInt(0))).toBe(
      journal.lines.reduce((a, l) => a + minor(l.credit), BigInt(0)),
    );
    await page.getByRole("button", { name: "Talent", exact: true }).click();
    const download = page.waitForEvent("download");
    await page
      .getByRole("button", { name: "Export current page (up to 10 records)" })
      .click();
    expect((await download).suggestedFilename()).toMatch(/^talent-.*\.csv$/);
    await page
      .getByText("My talent workflow notifications", { exact: true })
      .click();
    await expect(
      page.getByText("Committed in-app notices.", { exact: false }),
    ).toBeVisible();
    writeFileSync(
      "docs/evidence/talent-authoritative-browser-result.json",
      JSON.stringify(
        {
          employeeId: "E002",
          sourceId: sheetId,
          planId,
          runId: run.id,
          gross: run.gross,
          net: run.net,
          journalId: journal.id,
          journalStatus: journal.status,
          paymentStatus: linked.paymentStatus,
          statutoryProcessing: "NOT_CONFIGURED",
          realPayrollConsumption: true,
          developmentOnly: true,
        },
        null,
        2,
      ) + "\n",
    );
  } finally {
    await hr.dispose();
    await reviewer.dispose();
    await worker.dispose();
    await a.close();
  }
});
