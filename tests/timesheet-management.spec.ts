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
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
async function headers(c: APIRequestContext) {
  const csrf = (await (await c.get("/api/csrf")).json()) as {
    headerName: string;
    token: string;
  };
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
  n += Number(process.env.ATLAS_TIME_WEEK_OFFSET ?? 0);
  const d = new Date();
  d.setUTCHours(0, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7) - n * 7);
  return d.toISOString().slice(0, 10);
}
async function draft(c: APIRequestContext, w: string, minutes = 60) {
  const entries = Array.from({ length: 7 }, (_, i) => {
    const d = new Date(`${w}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + i);
    return {
      date: d.toISOString().slice(0, 10),
      minutes: i === 0 ? minutes : 0,
      code: i === 0 ? "SYNTHETIC-COST" : "",
    };
  });
  const response = await c.post("/api/v1/time/sheets", {
    headers: await headers(c),
    data: {
      week: w,
      zone: "Etc/UTC",
      version: 0,
      entries,
      reason: "Synthetic expanded timesheet acceptance",
      idempotencyKey: crypto.randomUUID(),
    },
  });
  return (await checkResponse(
    response,
    "/api/v1/time/sheets",
    "post",
  )) as Sheet;
}
async function action(
  c: APIRequestContext,
  s: Sheet,
  action: string,
  comment: string,
) {
  const r = await c.post(`/api/v1/time/sheets/${s.id}/actions`, {
    headers: await headers(c),
    data: {
      version: s.version,
      action,
      comment,
      idempotencyKey: crypto.randomUUID(),
    },
  });
  return (await checkResponse(
    r,
    "/api/v1/time/sheets/{id}/actions",
    "post",
  )) as Sheet;
}
test("returned hours are corrected in the editor, resubmitted and independently approved", async ({
  page,
}) => {
  const worker = await client("sophia"),
    manager = await client("marcus");
  try {
    const w = week(75),
      d = await draft(worker, w),
      s = await action(worker, d, "SUBMIT", "Please review original time");
    await signIn(page, "marcus");
    await page
      .getByRole("button", { name: "Team approvals", exact: true })
      .click();
    await page
      .getByRole("button", {
        name: `Open timesheet for ${w} · E003`,
        exact: true,
      })
      .click();
    await page
      .getByRole("dialog", {
        name: "Timesheet details and costing",
        exact: true,
      })
      .getByRole("combobox", { name: "Timesheet decision", exact: true })
      .selectOption("RETURN");
    await page
      .getByRole("textbox", { name: /^Timesheet comment/ })
      .fill("Please correct the daily total");
    await page
      .getByLabel("I have checked this timesheet and its impact.")
      .check();
    await page
      .getByRole("button", { name: "Confirm timesheet decision", exact: true })
      .click();
    await expect(
      page.getByText("Timesheet returned.", { exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await signIn(page, "sophia");
    await page
      .getByRole("button", { name: "My timesheets", exact: true })
      .click();
    await page.getByLabel("Timesheet status").selectOption("RETURNED");
    await page
      .getByRole("button", {
        name: `Open timesheet for ${w} · E003`,
        exact: true,
      })
      .click();
    await expect(
      page.getByText("Decision by marcus: Please correct the daily total", {
        exact: true,
      }),
    ).toBeVisible();
    await expect(
      page.getByRole("button", {
        name: "Submit timesheet for approval",
        exact: true,
      }),
    ).toHaveCount(0);
    await page
      .getByRole("button", { name: "Edit draft hours", exact: true })
      .click();
    await page
      .getByLabel(`Working minutes for ${w}`, { exact: true })
      .fill("90");
    await page
      .getByLabel("Timesheet reason", { exact: false })
      .fill("Corrected synthetic recorded time");
    await page
      .getByRole("button", { name: "Save timesheet draft", exact: true })
      .click();
    await expect(
      page.getByRole("dialog", { name: "Edit timesheet", exact: true }),
    ).toBeHidden();
    await expect(
      page.getByText("Timesheet draft saved. Open it to submit for approval.", {
        exact: true,
      }),
    ).toBeVisible();
    await page.getByLabel("Timesheet status").selectOption("CORRECTED");
    await page
      .getByRole("button", {
        name: `Open timesheet for ${w} · E003`,
        exact: true,
      })
      .click();
    await page
      .getByRole("textbox", { name: /^Timesheet comment/ })
      .fill("Corrected as requested");
    await page
      .getByLabel("I have checked this timesheet and its impact.")
      .check();
    await page
      .getByRole("button", {
        name: "Submit timesheet for approval",
        exact: true,
      })
      .click();
    await expect(
      page.getByText("Timesheet submitted.", { exact: true }),
    ).toBeVisible();
    const current = (
      await (await worker.get(`/api/v1/time/sheets/${s.id}`)).json()
    ).sheet as Sheet;
    const approved = await action(
      manager,
      current,
      "APPROVE",
      "Reviewed corrected total",
    );
    expect(approved.totalMinutes).toBe(90);
    expect(
      (await (await worker.get(`/api/v1/time/sheets/${s.id}`)).json())
        .allocatedMinutes,
    ).toBe(90);
  } finally {
    await worker.dispose();
    await manager.dispose();
  }
});
test("HR controls persist with history; selected manager approvals commit once; mobile controls are accessible", async ({
  page,
}) => {
  const worker = await client("sophia"),
    manager = await client("marcus"),
    outsider = await client("outsider");
  try {
    await signIn(page, "amelia");
    await page
      .getByRole("button", { name: "Reporting periods", exact: true })
      .click();
    const w = week(76);
    await page.getByLabel("Reporting week starting Monday").fill(w);
    await expect(
      page.getByText(
        "No controls configured for this week. Local defaults apply.",
        { exact: true },
      ),
    ).toBeVisible();
    await page
      .getByLabel("Submission deadline", { exact: false })
      .fill(new Date(Date.now() + 86400000).toISOString());
    await page
      .getByLabel("Daily limit in minutes", { exact: false })
      .fill("120");
    await page
      .getByLabel("Weekly limit in minutes", { exact: false })
      .fill("600");
    await page
      .getByLabel("Control change reason", { exact: false })
      .fill("Synthetic review policy");
    await page
      .getByLabel("I reviewed the effect on pending and draft timesheets.")
      .check();
    await page
      .getByRole("button", { name: "Save period controls", exact: true })
      .click();
    await expect(
      page.getByRole("heading", { name: `Week of ${w} · Revision 1` }).first(),
    ).toBeVisible();
    const p = await worker.get(`/api/v1/time/periods/${w}`);
    await checkResponse(p, "/api/v1/time/periods/{week}");
    const controls = await p.json();
    expect(controls.current.dailyLimit).toBe(120);
    const missingFlag = await page.request.post("/api/v1/time/periods", {
      headers: await headers(page.request),
      data: {
        ...controls.current,
        closed: null,
        version: 1,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(missingFlag.status()).toBe(400);
    expect((await missingFlag.json()).fieldErrors.closed).toBeDefined();
    expect(
      (await (await outsider.get(`/api/v1/time/periods/${w}`)).json()).current,
    ).toBeNull();
    expect(
      (
        await worker.post("/api/v1/time/periods", {
          headers: await headers(worker),
          data: {
            ...controls.current,
            version: 1,
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(403);
    await page.setViewportSize({ width: 390, height: 844 });
    expect(
      (await new AxeBuilder({ page }).analyze()).violations.filter(
        (v) => v.impact === "serious" || v.impact === "critical",
      ),
    ).toEqual([]);
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBe(true);
    await page.screenshot({
      path: "docs/evidence/timesheet-period-mobile.png",
      fullPage: true,
    });
    await page.setViewportSize({ width: 1440, height: 1000 });
    const first = await action(
      worker,
      await draft(worker, w),
      "SUBMIT",
      "First batch candidate",
    );
    const second = await action(
      worker,
      await draft(worker, week(77)),
      "SUBMIT",
      "Second batch candidate",
    );
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await signIn(page, "marcus");
    await page
      .getByRole("button", { name: "Team approvals", exact: true })
      .click();
    const form = page.getByRole("form", {
      name: "Review selected timesheets",
      exact: true,
    });
    await form.getByLabel(`E003 · ${w} · 1h 0m`, { exact: true }).check();
    await form
      .getByLabel(`E003 · ${week(77)} · 1h 0m`, { exact: true })
      .check();
    await form
      .getByLabel("Batch review reason", { exact: false })
      .fill("Reviewed both synthetic submissions");
    await form
      .getByLabel("I reviewed each selected submission and its impact.")
      .check();
    await form
      .getByRole("button", { name: "Confirm selected timesheets", exact: true })
      .click();
    await expect(
      page.getByText("Selected timesheet decisions saved.", { exact: true }),
    ).toBeVisible();
    for (const s of [first, second])
      expect(
        (await (await worker.get(`/api/v1/time/sheets/${s.id}`)).json())
          .allocatedMinutes,
      ).toBe(60);
    const body = {
      sheets: [
        { id: first.id, version: 1 },
        { id: second.id, version: 1 },
      ],
      action: "APPROVE",
      comment: "Stale second batch",
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await manager.post("/api/v1/time/sheets/selected-actions", {
          headers: await headers(manager),
          data: body,
        })
      ).status(),
    ).toBe(409);
  } finally {
    await worker.dispose();
    await manager.dispose();
    await outsider.dispose();
  }
});

test("worker recalls a pending submission through the connected detail screen", async ({
  page,
}) => {
  const worker = await client("sophia");
  try {
    const w = week(78),
      submitted = await action(
        worker,
        await draft(worker, w),
        "SUBMIT",
        "Pending worker review",
      );
    await signIn(page, "sophia");
    await page
      .getByRole("button", { name: "My timesheets", exact: true })
      .click();
    await page.getByLabel("Timesheet status").selectOption("SUBMITTED");
    await page
      .getByRole("button", {
        name: `Open timesheet for ${w} · E003`,
        exact: true,
      })
      .click();
    await page
      .getByRole("textbox", { name: /^Timesheet comment/ })
      .fill("Need to revise this pending week");
    await page
      .getByLabel("I have checked this timesheet and its impact.")
      .check();
    await page
      .getByRole("button", { name: "Recall timesheet", exact: true })
      .click();
    await expect(
      page.getByText("Timesheet draft.", { exact: true }),
    ).toBeVisible();
    const current = await (
      await worker.get(`/api/v1/time/sheets/${submitted.id}`)
    ).json();
    expect(current.sheet.status).toBe("DRAFT");
    expect(current.allocatedMinutes).toBe(0);
  } finally {
    await worker.dispose();
  }
});
