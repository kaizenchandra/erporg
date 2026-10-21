import { amount } from "../features/finance/contracts";
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
import type {
  Journal,
  Page as Records,
  TrialBalance,
} from "../features/finance/contracts";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
test.describe.configure({ mode: "serial" });
async function headers(c: APIRequestContext) {
  const t = (await (await c.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
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
async function post(
  c: APIRequestContext,
  path: string,
  data: Record<string, unknown>,
) {
  const r = await c.post(path, {
    headers: await headers(c),
    data: {
      ...data,
      idempotencyKey: data.idempotencyKey ?? crypto.randomUUID(),
    },
  });
  expect(r.status()).toBe(200);
  return r;
}
async function signIn(page: Page, user: string, view = "Payroll") {
  await page.goto(origin);
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: view, exact: true }).click();
}
async function openPay(page: Page, id: string) {
  await page
    .getByRole("button", { name: `View payroll result ${id}`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
}
async function payAction(page: Page, action: string) {
  const d = page.getByRole("dialog");
  await d
    .getByRole("combobox", { name: /Payroll action/ })
    .selectOption(action);
  await d
    .getByLabel("Payroll decision reason")
    .fill(`Synthetic authorised ${action}`);
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
async function openJournal(page: Page, id: string) {
  await page
    .getByRole("button", { name: `Review journal ${id}`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
}
async function journalAction(page: Page, action: string) {
  const d = page.getByRole("dialog");
  await d
    .getByRole("combobox", { name: /Journal action/ })
    .selectOption(action);
  await d
    .getByLabel("Decision reason", { exact: false })
    .fill(`Synthetic authorised ${action}`);
  await d
    .getByRole("checkbox", {
      name: "I reviewed the journal scope, balanced lines, period and financial impact.",
    })
    .check();
  await d
    .getByRole("button", { name: "Confirm journal action", exact: true })
    .click();
  await expect(d).toBeHidden();
}
function cents(value: string) {
  const [whole, decimal = ""] = value.split(".");
  return BigInt(whole) * BigInt(100) + BigInt(decimal.padEnd(2, "0"));
}
let sourceId = "",
  runId = "",
  payJournalId = "";
test.beforeAll(async () => {
  const hr = await client("amelia"),
    reviewer = await client("olivia"),
    worker = await client("sophia"),
    manager = await client("marcus");
  try {
    const today = new Date().toISOString().slice(0, 10);
    await post(hr, "/api/v1/legal-entities", {
      entity: {
        code: "DEMO-FIN",
        name: "Synthetic financial browser company",
        countryCode: "US",
        currencyCode: "USD",
        timeZone: "Etc/UTC",
        effectiveFrom: today,
      },
      reason: "Demonstration reference only",
    });
    const employee = (await (
      await hr.get("/api/v1/employees/E003")
    ).json()) as { version: number };
    const assignment = (await (
      await post(hr, "/api/v1/requests", {
        employeeId: "E003",
        field: "legalEntityCode",
        newValue: "DEMO-FIN",
        effectiveDate: today,
        reason: "Synthetic entity scope",
        version: employee.version,
      })
    ).json()) as { id: string };
    await post(reviewer, `/api/v1/requests/${assignment.id}/decision`, {
      decision: "APPROVED",
      comment: "Independent declared-entity review",
    });
    const monday = new Date(`${today}T00:00:00Z`);
    monday.setUTCDate(monday.getUTCDate() - ((monday.getUTCDay() + 6) % 7));
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
    const sheet = (await (
      await post(worker, "/api/v1/time/sheets", {
        week: monday.toISOString().slice(0, 10),
        zone: "Etc/UTC",
        version: 0,
        entries,
        reason: "Synthetic approved payroll source",
      })
    ).json()) as { id: string };
    sourceId = sheet.id;
    await post(worker, `/api/v1/time/sheets/${sourceId}/actions`, {
      version: 0,
      action: "SUBMIT",
      comment: "Actual synthetic time",
    });
    await post(manager, `/api/v1/time/sheets/${sourceId}/actions`, {
      version: 1,
      action: "APPROVE",
      comment: "Independent synthetic time review",
    });
  } finally {
    await Promise.all([
      hr.dispose(),
      reviewer.dispose(),
      worker.dispose(),
      manager.dispose(),
    ]);
  }
});
test("approved time becomes a finalized payslip and a separately approved, posted and reconciled journal", async ({
  browser,
  page,
}) => {
  test.setTimeout(120000);
  const approvalContext = await browser.newContext(),
    financeContext = await browser.newContext(),
    employeeContext = await browser.newContext();
  const approval = await approvalContext.newPage(),
    finance = await financeContext.newPage(),
    employee = await employeeContext.newPage(),
    hr = await client("amelia"),
    worker = await client("sophia");
  try {
    await signIn(page, "amelia", "Finance & operations");
    await page
      .getByRole("button", { name: "Ledger configuration", exact: true })
      .click();
    await page
      .getByLabel("Ledger configuration reason")
      .fill("Synthetic fixed USD chart and dimension");
    await page
      .getByRole("checkbox", {
        name: "I am configuring synthetic demonstration records.",
      })
      .check();
    await page
      .getByRole("button", { name: "Create demonstration ledger", exact: true })
      .click();
    await expect(
      page.getByText("6000 · Demonstration wage expense", { exact: false }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Payroll", exact: true }).click();
    await page
      .getByRole("button", { name: "Calculate payroll", exact: true })
      .click();
    const d = page.getByRole("dialog");
    await d.getByLabel("Approved timesheet ID").fill(sourceId);
    await d.getByLabel("Calculation source").selectOption("HOURLY");
    await d.getByLabel("Hourly rate (USD)").fill("20.125");
    await d.getByLabel("Voluntary deduction (USD)").fill("0.46");
    await d
      .getByLabel("Payroll calculation reason")
      .fill(
        "Synthetic independently established 20.46 gross / 20.00 net fixture",
      );
    await d
      .getByRole("button", {
        name: "Calculate demonstration result",
        exact: true,
      })
      .click();
    await expect(d).toBeHidden();
    const records = (await checkResponse(
      await hr.get("/api/v1/payroll/runs?entity=DEMO-FIN"),
      "/api/v1/payroll/runs",
    )) as Records<Run>;
    expect(records.items).toHaveLength(1);
    let run = records.items[0];
    runId = run.id;
    expect(run.gross).toBe("20.46");
    expect(run.net).toBe("20.00");
    expect(run.paymentStatus).toBe("NOT_CONFIGURED");
    await openPay(page, runId);
    await expect(
      d.getByRole("heading", { name: /Gross USD 20.46/ }),
    ).toBeVisible();
    await payAction(page, "SUBMIT");
    await signIn(approval, "olivia");
    await openPay(approval, runId);
    await payAction(approval, "FINALIZE");
    await signIn(employee, "sophia");
    await openPay(employee, runId);
    const downloadPromise = employee.waitForEvent("download");
    await employee
      .getByRole("dialog")
      .getByRole("button", { name: "Download released demonstration payslip" })
      .click();
    expect((await downloadPromise).suggestedFilename()).toContain(
      "demo-payslip-",
    );
    const doc = (await checkResponse(
      await worker.post(`/api/v1/payroll/runs/${runId}/payslip`, {
        headers: await headers(worker),
      }),
      "/api/v1/payroll/runs/{id}/payslip",
      "post",
    )) as { content: string };
    expect(doc.content).toContain("Net 20.00");
    expect(doc.content).toContain("NOT STATUTORY OR EVIDENCE OF PAYMENT");
    await page.getByRole("button", { name: "Refresh payroll" }).click();
    await openPay(page, runId);
    await payAction(page, "CREATE_JOURNAL");
    run = (await checkResponse(
      await hr.get(`/api/v1/payroll/runs/${runId}`),
      "/api/v1/payroll/runs/{id}",
    )) as Run;
    expect(run.journalId).toBeTruthy();
    payJournalId = run.journalId!;
    await page
      .getByRole("button", { name: "Finance & operations", exact: true })
      .click();
    await openJournal(page, payJournalId);
    await journalAction(page, "SUBMIT");
    await approval
      .getByRole("button", { name: "Finance & operations", exact: true })
      .click();
    await openJournal(approval, payJournalId);
    await journalAction(approval, "APPROVE");
    await signIn(finance, "marcus", "Finance & operations");
    await openJournal(finance, payJournalId);
    await journalAction(finance, "POST");
    await finance
      .getByRole("button", { name: "Trial balance", exact: true })
      .click();
    await expect(
      finance
        .getByRole("region", { name: "Trial balance" })
        .getByRole("status"),
    ).toContainText("Debits 20.46 · Credits 20.46");
    await finance.setViewportSize({ width: 390, height: 844 });
    const checks = await new AxeBuilder({ page: finance })
      .include('[aria-label="Finance workspace"]')
      .analyze();
    expect(
      checks.violations.filter((v) =>
        ["critical", "serious"].includes(v.impact ?? ""),
      ),
    ).toEqual([]);
    await finance.screenshot({
      path: "docs/evidence/payfin-trial-balance-mobile.png",
      fullPage: true,
    });
    await employee
      .getByRole("dialog")
      .getByRole("button", { name: "Close panel" })
      .click();
    await employee
      .getByRole("button", { name: "Refresh payroll", exact: true })
      .click();
    await expect(
      employee.getByText("Accounting Journal prepared", { exact: false }),
    ).toBeVisible();
    await employee.setViewportSize({ width: 390, height: 844 });
    await employee.screenshot({
      path: "docs/evidence/payfin-payslips-mobile.png",
      fullPage: true,
    });
  } finally {
    await Promise.all([
      approvalContext.close(),
      financeContext.close(),
      employeeContext.close(),
      hr.dispose(),
      worker.dispose(),
    ]);
  }
});
test("manual journal is returned, corrected, independently posted and reversed without editing posted history", async ({
  browser,
  page,
}) => {
  test.setTimeout(120000);
  const reviewerContext = await browser.newContext(),
    posterContext = await browser.newContext();
  const reviewer = await reviewerContext.newPage(),
    poster = await posterContext.newPage(),
    hr = await client("amelia");
  try {
    await signIn(page, "amelia", "Finance & operations");
    await page
      .getByRole("button", { name: "Prepare manual journal", exact: true })
      .click();
    const d = page.getByRole("dialog");
    await d.getByLabel("Debit 1", { exact: true }).fill("100.00");
    await d.getByLabel("Credit 2", { exact: true }).fill("100.00");
    await d.getByLabel("Line memo 1").fill("Synthetic expense");
    await d.getByLabel("Line memo 2").fill("Synthetic bank offset");
    await d
      .getByLabel("Journal reason")
      .fill("Browser manual journal lifecycle");
    await d
      .getByRole("button", { name: "Save journal draft", exact: true })
      .click();
    await expect(d).toBeHidden();
    let records = (await checkResponse(
      await hr.get("/api/v1/finance/journals?entity=DEMO-FIN"),
      "/api/v1/finance/journals",
    )) as Records<Journal>;
    let j = records.items.find((x) => x.sourceType === "MANUAL")!;
    expect(j).toBeTruthy();
    await openJournal(page, j.id);
    await journalAction(page, "SUBMIT");
    await signIn(reviewer, "olivia", "Finance & operations");
    await openJournal(reviewer, j.id);
    await journalAction(reviewer, "RETURN");
    await page.getByRole("button", { name: "Refresh journals" }).click();
    await openJournal(page, j.id);
    await d.getByRole("button", { name: "Edit journal", exact: true }).click();
    await d
      .getByLabel("Journal reason")
      .fill("Corrected manual journal explanation");
    await d
      .getByRole("button", { name: "Save journal draft", exact: true })
      .click();
    await expect(d).toBeHidden();
    await openJournal(page, j.id);
    await journalAction(page, "SUBMIT");
    await reviewer.getByRole("button", { name: "Refresh journals" }).click();
    await openJournal(reviewer, j.id);
    await journalAction(reviewer, "APPROVE");
    await signIn(poster, "marcus", "Finance & operations");
    await openJournal(poster, j.id);
    await journalAction(poster, "POST");
    await page.getByRole("button", { name: "Refresh journals" }).click();
    await openJournal(page, j.id);
    await journalAction(page, "REVERSE");
    records = (await (
      await hr.get("/api/v1/finance/journals?entity=DEMO-FIN")
    ).json()) as Records<Journal>;
    const reversal = records.items.find((x) => x.reversalOf === j.id)!;
    expect(reversal.status).toBe("DRAFT");
    await openJournal(page, reversal.id);
    await journalAction(page, "SUBMIT");
    await reviewer.getByRole("button", { name: "Refresh journals" }).click();
    await openJournal(reviewer, reversal.id);
    await journalAction(reviewer, "APPROVE");
    await poster.getByRole("button", { name: "Refresh journals" }).click();
    await openJournal(poster, reversal.id);
    await journalAction(poster, "POST");
    j = (await (
      await hr.get(`/api/v1/finance/journals/${j.id}`)
    ).json()) as Journal;
    expect(j.status).toBe("REVERSED");
    expect(j.lines[0].debit).toBe("100.00");
    const postedCounter = (await (
      await hr.get(`/api/v1/finance/journals/${reversal.id}`)
    ).json()) as Journal;
    expect(
      (
        await hr.post(`/api/v1/finance/journals/${postedCounter.id}/actions`, {
          headers: await headers(hr),
          data: {
            version: postedCounter.version,
            action: "REVERSE",
            reason: "Unsupported reversal chain must be denied",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(409);

    const today = new Date().toISOString().slice(0, 10);
    const balance = (await checkResponse(
      await hr.get(
        `/api/v1/finance/trial-balance?entity=DEMO-FIN&from=${today}&through=${today}`,
      ),
      "/api/v1/finance/trial-balance",
    )) as TrialBalance;
    expect(balance.debit).toBe(balance.credit);
    const wage = balance.items.find((r) => r.account === "6000")!;
    expect(cents(wage.debit) - cents(wage.credit)).toBe(BigInt(2046));
  } finally {
    await Promise.all([
      reviewerContext.close(),
      posterContext.close(),
      hr.dispose(),
    ]);
  }
});
test("entity grants, payroll ownership, CSRF and period closure are enforced by real APIs and UI", async ({
  page,
}) => {
  const hr = await client("amelia"),
    worker = await client("sophia"),
    manager = await client("marcus"),
    outsider = await client("outsider");
  try {
    expect(
      (await worker.get(`/api/v1/finance/journals/${payJournalId}`)).status(),
    ).toBe(403);
    expect((await outsider.get(`/api/v1/payroll/runs/${runId}`)).status()).toBe(
      403,
    );
    expect((await manager.get(`/api/v1/payroll/runs/${runId}`)).status()).toBe(
      403,
    );
    expect(
      (
        await hr.post("/api/v1/finance/periods", {
          data: { entity: "DEMO-FIN" },
        })
      ).status(),
    ).toBe(403);
    await signIn(page, "amelia", "Finance & operations");
    await page
      .getByRole("button", { name: "Posting periods", exact: true })
      .click();
    await expect(
      page.getByText("Open · Revision 0", { exact: true }),
    ).toBeVisible();
    await page
      .getByLabel("Period change reason")
      .fill("Synthetic authorised close");
    await page
      .getByRole("checkbox", {
        name: "I reviewed the entity, month and posting impact.",
      })
      .check();
    await page
      .getByRole("button", { name: "Save posting period control" })
      .click();
    await expect(
      page.getByText("Closed · Revision 1", { exact: true }),
    ).toBeVisible();
    const today = new Date().toISOString().slice(0, 10);
    const failed = await hr.post("/api/v1/finance/journals", {
      headers: await headers(hr),
      data: {
        entity: "DEMO-FIN",
        postingDate: today,
        version: 0,
        lines: [
          {
            account: "6000",
            costCentre: "ENGINEERING",
            debit: "1.00",
            credit: "0",
            memo: "Closed test",
          },
          {
            account: "1000",
            costCentre: "",
            debit: "0",
            credit: "1.00",
            memo: "Closed test",
          },
        ],
        reason: "Must fail for closed month",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(failed.status()).toBe(409);
    await page
      .getByRole("checkbox", { name: "Close this posting month" })
      .uncheck();
    await page
      .getByLabel("Period change reason")
      .fill("Synthetic authorised reopening");
    await page
      .getByRole("checkbox", {
        name: "I reviewed the entity, month and posting impact.",
      })
      .check();
    await page
      .getByRole("button", { name: "Save posting period control" })
      .click();
    await expect(
      page.getByText("Open · Revision 2", { exact: true }),
    ).toBeVisible();
  } finally {
    await Promise.all([
      hr.dispose(),
      worker.dispose(),
      manager.dispose(),
      outsider.dispose(),
    ]);
  }
});

test("financial presentation preserves cents beyond JavaScript safe-integer precision", () => {
  expect(amount("9007199254740993.01")).toBe("9,007,199,254,740,993.01");
  expect(amount("0")).toBe("0.00");
  expect(amount("-10.25")).toBe("-10.25");
});
