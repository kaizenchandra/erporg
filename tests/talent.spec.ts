import {
  test,
  expect,
  request,
  type APIRequestContext,
  type Page,
  type Locator,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type {
  Requisition,
  Review,
  Course,
  Compensation,
  RecordPage,
} from "../features/talent/contracts";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
test.describe.configure({ mode: "serial" });
test.setTimeout(60000);
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
    data: {
      ...data,
      idempotencyKey: data.idempotencyKey ?? crypto.randomUUID(),
    },
  });
  expect(r.status(), await r.text()).toBe(200);
  const documented = path.replace(
    /\/[0-9a-f]{8}-[0-9a-f-]{27}(?=\/|$)/g,
    "/{id}",
  );
  await checkResponse(r, documented, "post");
  return (await r.json()) as T;
}
async function signIn(p: Page, user: string) {
  await p.goto(origin);
  await p.getByLabel("Workspace account").selectOption(user);
  await p.getByRole("button", { name: "Enter workspace", exact: true }).click();
  await expect(
    p.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await p.getByRole("button", { name: "Talent", exact: true }).click();
  await expect(
    p.getByRole("heading", { name: "Build your next chapter." }),
  ).toBeVisible();
  p.on("dialog", (d) => d.accept());
}
async function tab(p: Page, name: string) {
  await p
    .getByRole("navigation", { name: "Talent views" })
    .getByRole("button", { name, exact: true })
    .click();
}
function detail(p: Page) {
  return p.getByRole("region", { name: "Talent record detail" });
}
function form(p: Page | Locator, button: string) {
  return p.locator("form").filter({ hasText: button });
}
async function submit(
  p: Page | Locator,
  button: string,
  reason = "Synthetic browser verification",
) {
  const f = form(p, button);
  await f.getByLabel("Reason for this action").fill(reason);
  const page = "page" in p ? p.page() : p;
  const [response] = await Promise.all([
    page.waitForResponse(
      (r) =>
        r.request().method() === "POST" && r.url().includes("/api/v1/talent/"),
    ),
    f.getByRole("button", { name: button, exact: true }).click(),
  ]);
  if (response.ok()) {
    const result = (await response.json()) as { meta?: { version: number } };
    if (result.meta && !response.url().endsWith("/talent/learning"))
      await expect(
        detail(page).getByText(
          `Revision ${result.meta.version} · prepared by`,
          { exact: false },
        ),
      ).toBeVisible();
  }
}
async function open(p: Page, name: string) {
  await p.getByRole("button", { name: `Open ${name}`, exact: true }).click();
  await expect(detail(p)).toBeVisible();
}
const suffix = Date.now().toString();
const today = new Date().toISOString().slice(0, 10);
let hr: APIRequestContext,
  reviewer: APIRequestContext,
  worker: APIRequestContext,
  manager: APIRequestContext,
  outsider: APIRequestContext;
test.beforeAll(async () => {
  hr = await client("amelia");
  reviewer = await client("olivia");
  worker = await client("sophia");
  manager = await client("marcus");
  outsider = await client("outsider");
});
test.afterAll(async () => {
  await Promise.all(
    [hr, reviewer, worker, manager, outsider].map((c) => c.dispose()),
  );
});
test("recruitment UI converts a reviewed candidate into one employee and completes dependent joining tasks", async ({
  browser,
}) => {
  const a = await browser.newContext();
  const p = await a.newPage();
  await signIn(p, "amelia");
  await p
    .getByRole("button", { name: "Create recruitment", exact: true })
    .click();
  await p.getByLabel("Job title").fill(`Talent analyst ${suffix}`);
  await p.getByLabel("Department", { exact: false }).fill("Design");
  await p.getByLabel("Work location").fill("London, UK");
  await p.getByLabel("Hiring manager").selectOption("E002");
  await p.getByLabel("Employment start date").fill(today);
  await submit(p, "Save requisition");
  await expect(detail(p).getByText("DRAFT", { exact: true })).toBeVisible();
  await detail(p).getByLabel("Workflow action").selectOption("SUBMIT");
  await submit(detail(p), "Confirm workflow action");
  await expect(
    detail(p).getByText("Pending review", { exact: true }),
  ).toBeVisible();
  const rows = (await (
    await hr.get("/api/v1/talent/requisitions")
  ).json()) as RecordPage;
  let r = rows.items.find(
    (x) => "title" in x && x.title === `Talent analyst ${suffix}`,
  ) as Requisition;
  r = await post<Requisition>(
    reviewer,
    `/api/v1/talent/requisitions/${r.meta.id}/actions`,
    {
      version: r.meta.version,
      action: "APPROVE",
      reason: "Independent vacancy approval",
    },
  );
  await p.getByRole("button", { name: "Refresh talent" }).click();
  await expect(detail(p).getByText("OPEN", { exact: true })).toBeVisible();
  await detail(p).getByText("Add a candidate", { exact: true }).click();
  await p.getByLabel("Candidate name").fill("Zoe Synthetic");
  await p.getByLabel("Candidate email").fill(`talent-${suffix}@example.test`);
  await p.getByLabel("Candidate phone").fill("+1 202 555 8888");
  await submit(detail(p), "Add candidate");
  for (const action of ["INTERVIEW", "OFFER", "ACCEPTED", "SEND_HIRE"]) {
    const f = form(detail(p), "Update Zoe Synthetic");
    await f.getByLabel("Candidate action").selectOption(action);
    if (action === "INTERVIEW") {
      await f.getByLabel("Interview date/time").fill(`${today}T10:00:00Z`);
      await f
        .getByLabel("Interview plan")
        .fill("Synthetic interview with hiring manager");
    }
    if (action === "OFFER")
      await f
        .getByLabel("Offer terms")
        .fill("Synthetic employment offer; no payment");
    await submit(detail(p), "Update Zoe Synthetic");
    await expect(
      detail(p).getByRole("heading", {
        name: `Zoe Synthetic · ${action === "SEND_HIRE" ? "HIRE_SUBMITTED" : action}`,
        exact: true,
      }),
    ).toBeVisible();
  }
  r = await (await hr.get(`/api/v1/talent/requisitions/${r.meta.id}`)).json();
  const candidate = r.candidates[0];
  r = await post<Requisition>(
    reviewer,
    `/api/v1/talent/requisitions/${r.meta.id}/candidates`,
    {
      version: r.meta.version,
      candidateId: candidate.id,
      action: "APPROVE_HIRE",
      reason: "Independent Core HR hire approval",
    },
  );
  const employee = r.candidates[0].employeeId;
  await tab(p, "Onboarding");
  await open(p, `Joining plan · ${employee}`);
  await detail(p).getByText("Complete joining form", { exact: true }).click();
  await p
    .getByLabel("Joining information and acknowledgements")
    .fill("Equipment and privacy guidance acknowledged");
  await submit(detail(p), "Save joining form");
  for (const title of ["Collect equipment", "Complete orientation"]) {
    await detail(p).getByText("Add joining task", { exact: true }).click();
    await p.getByLabel("Task title").fill(title);
    await p.getByLabel("Task assignee").selectOption("E001");
    if (title === "Complete orientation")
      await p.getByRole("checkbox", { name: "Collect equipment" }).check();
    await submit(detail(p), "Add task");
    await expect(
      detail(p).getByRole("heading", {
        name: `${title} · Pending`,
        exact: true,
      }),
    ).toBeVisible();
  }
  const bad = await hr.get("/api/v1/talent/onboarding");
  expect(bad.status()).toBe(200);
  for (const title of ["Collect equipment", "Complete orientation"]) {
    const f = form(detail(p), `Complete ${title}`);
    await f.getByLabel("Completion evidence").fill("Synthetic task completed");
    await submit(detail(p), `Complete ${title}`);
    await expect(
      detail(p).getByRole("heading", {
        name: `${title} · Complete`,
        exact: true,
      }),
    ).toBeVisible();
  }
  await expect(detail(p).getByText(/Employee revision \d+/)).toBeVisible();
  await submit(detail(p), "Complete onboarding and activate employment");
  await expect(detail(p).getByText("COMPLETED", { exact: true })).toBeVisible();
  const e = await (await hr.get(`/api/v1/employees/${employee}`)).json();
  expect(e.status).toBe("Active");
  await a.close();
});
test("performance UI saves employee goals, manager feedback, return, correction and final rating", async ({
  browser,
}) => {
  let r = await post<Review>(manager, "/api/v1/talent/reviews", {
    employeeId: "E003",
    cycle: `Growth cycle ${suffix}`,
    from: today,
    through: today,
    minimumRating: 1,
    maximumRating: 5,
    reason: "Open scoped review",
  });
  const wc = await browser.newContext();
  const w = await wc.newPage();
  await signIn(w, "sophia");
  await tab(w, "Performance");
  await open(w, `${r.cycle} · E003`);
  await detail(w)
    .getByLabel("Goal", { exact: false })
    .fill("Deliver accessible talent workflows");
  await submit(detail(w), "Add goal");
  r = await (await worker.get(`/api/v1/talent/reviews/${r.meta.id}`)).json();
  r = await post<Review>(
    manager,
    `/api/v1/talent/reviews/${r.meta.id}/actions`,
    {
      version: r.meta.version,
      action: "FEEDBACK",
      goalId: r.goals[0].id,
      feedback: "Good progress; add completion evidence",
      reason: "Manager feedback",
    },
  );
  await w.getByRole("button", { name: "Refresh talent" }).click();
  await detail(w)
    .getByLabel("Self review", { exact: false })
    .fill("Completed the delivery");
  await submit(detail(w), "Submit self review");
  await expect(
    detail(w).getByText("Pending review", { exact: true }),
  ).toBeVisible();
  const mc = await browser.newContext();
  const m = await mc.newPage();
  await signIn(m, "marcus");
  await tab(m, "Performance");
  await m.getByLabel("Worker filter").selectOption("E003");
  await open(m, `${r.cycle} · E003`);
  await detail(m).getByLabel("Review decision").selectOption("RETURN");
  await submit(
    detail(m),
    "Record review decision",
    "Add independently verifiable evidence",
  );
  await expect(detail(m).getByText("RETURNED", { exact: true })).toBeVisible();
  await w.getByRole("button", { name: "Refresh talent" }).click();
  await detail(w)
    .getByLabel("Self review", { exact: false })
    .fill("Completed delivery with recorded test evidence");
  await submit(detail(w), "Submit self review");
  await expect(
    detail(w).getByText("Pending review", { exact: true }),
  ).toBeVisible();
  await m.getByRole("button", { name: "Refresh talent" }).click();
  await detail(m).getByLabel("Review decision").selectOption("APPROVE");
  await detail(m).getByLabel("Final rating").fill("4");
  await submit(detail(m), "Record review decision");
  await expect(
    detail(m).getByText("Final rating: 4", { exact: true }),
  ).toBeVisible();
  await wc.close();
  await mc.close();
});
test("learning UI persists eligible assignment and completion with expiry", async ({
  browser,
}) => {
  const c = await post<Course>(hr, "/api/v1/talent/courses", {
    title: `Secure delivery ${suffix}`,
    description:
      "Read the synthetic training guidance and provide completion evidence",
    role: "EMPLOYEE",
    validDays: 30,
    reason: "Publish learning",
  });
  const a = await browser.newContext();
  const p = await a.newPage();
  await signIn(p, "sophia");
  await tab(p, "Course catalogue");
  await open(p, c.title);
  await detail(p).getByLabel("Completion due date").fill(today);
  await submit(detail(p), "Assign course");
  await tab(p, "Assigned learning");
  await open(p, `${c.title} · E003`);
  await detail(p)
    .getByLabel("Completion evidence and acknowledgement")
    .fill("Read training and completed synthetic assessment");
  await submit(detail(p), "Record learning completion");
  await expect(detail(p).getByText("COMPLETED", { exact: true })).toBeVisible();
  await p.reload();
  await p.getByRole("button", { name: "Talent", exact: true }).click();
  await tab(p, "Assigned learning");
  await open(p, `${c.title} · E003`);
  await expect(detail(p).getByText(/Expires \d{4}-/)).toBeVisible();
  await a.close();
});
test("compensation budget errors preserve forms, independent approval reaches payroll intake, and planning is persisted", async ({
  browser,
}) => {
  const a = await browser.newContext();
  const p = await a.newPage();
  await signIn(p, "amelia");
  await tab(p, "Compensation");
  await p
    .getByRole("button", { name: "Create compensation", exact: true })
    .click();
  await p.getByLabel("Compensation plan name").fill(`Growth awards ${suffix}`);
  await p.getByLabel("Planning budget").fill("100.00");
  await p.getByLabel("Worker", { exact: false }).last().selectOption("E003");
  await p.getByLabel("Current annual salary").fill("10000.00");
  await p.getByLabel("Proposed annual salary").fill("11000.00");
  await p.getByLabel("One-time bonus").fill("500.25");
  await p.getByLabel("Effective date", { exact: false }).fill(today);
  await submit(p, "Save compensation plan");
  await expect(
    p.getByRole("region", { name: "Talent workspace" }).getByRole("alert"),
  ).toContainText("budget");
  await expect(p.getByLabel("Proposed annual salary")).toHaveValue("11000.00");
  await expect(
    p.getByLabel("Planning budget", { exact: false }),
  ).toHaveAttribute("aria-invalid", "true");
  await p.getByLabel("Planning budget").fill("1500.25");
  await submit(p, "Save compensation plan");
  await expect(detail(p).getByText(/proposed cost 1,500.25/)).toBeVisible();
  await submit(detail(p), "Confirm workflow action");
  const rows = (await (
    await hr.get("/api/v1/talent/compensation")
  ).json()) as RecordPage;
  let plan = rows.items.find(
    (x) => "title" in x && x.title === `Growth awards ${suffix}`,
  ) as Compensation;
  const denied = await hr.post(
    `/api/v1/talent/compensation/${plan.meta.id}/actions`,
    {
      headers: await headers(hr),
      data: {
        version: plan.meta.version,
        action: "APPROVE",
        reason: "Self approval denied",
        idempotencyKey: crypto.randomUUID(),
      },
    },
  );
  expect(denied.status()).toBe(403);
  plan = await post<Compensation>(
    reviewer,
    `/api/v1/talent/compensation/${plan.meta.id}/actions`,
    {
      version: plan.meta.version,
      action: "APPROVE",
      reason: "Independent approved budget",
    },
  );
  await p.getByRole("button", { name: "Refresh talent" }).click();
  await expect(detail(p).getByRole("status")).toContainText("RECEIVED");
  expect(
    (await (await hr.get("/api/v1/payroll/talent-inputs")).json()).some(
      (i: { sourceId: string }) => i.sourceId === plan.meta.id,
    ),
  ).toBe(true);
  await tab(p, "Succession");
  await p
    .getByRole("button", { name: "Create succession", exact: true })
    .click();
  await p
    .getByLabel("Critical position or role")
    .fill(`Engineering lead ${suffix}`);
  await p.getByLabel("Worker", { exact: false }).last().selectOption("E003");
  await p
    .getByLabel("Talent profile and demonstrated skills")
    .fill("Delivery and mentoring");
  await p
    .getByLabel("Readiness assessment")
    .fill("Leadership demonstrated during projects");
  await submit(p, "Save succession plan");
  await expect(
    detail(p).getByText("Talent profile: Delivery and mentoring"),
  ).toBeVisible();
  await detail(p)
    .getByText("Update a nominee or add a successor", { exact: true })
    .click();
  await detail(p).getByLabel("Worker", { exact: false }).selectOption("E003");
  await detail(p)
    .getByLabel("Current talent profile")
    .fill("Delivery, mentoring and coaching");
  await detail(p)
    .getByLabel("Updated readiness assessment")
    .fill("Ready following demonstrated leadership");
  await submit(detail(p), "Record readiness assessment");
  await expect(
    detail(p).getByText("Talent profile: Delivery, mentoring and coaching"),
  ).toBeVisible();
  await tab(p, "Workforce planning");
  await p
    .getByRole("button", { name: "Create workforce planning", exact: true })
    .click();
  await p.getByLabel("Scenario name").fill(`Engineering growth ${suffix}`);
  await p.getByRole("checkbox", { name: /Sophia Martinez/ }).check();
  await p.getByLabel("Cost dimension").fill("Engineering");
  await p.getByLabel("Baseline headcount").fill("1");
  await p.getByLabel("Proposed headcount").fill("2");
  await p.getByLabel("Annual unit cost").fill("12345.67");
  await submit(p, "Calculate and save scenario");
  await expect(detail(p).getByText("24,691.34", { exact: true })).toBeVisible();
  await p.setViewportSize({ width: 390, height: 844 });
  const axe = await new AxeBuilder({ page: p })
    .include('[aria-label="Talent workspace"]')
    .analyze();
  expect(
    axe.violations.filter((v) =>
      ["serious", "critical"].includes(v.impact ?? ""),
    ),
  ).toEqual([]);
  await p.screenshot({
    path: "docs/evidence/talent-scenario-mobile.png",
    fullPage: true,
  });
  await p.getByRole("button", { name: "Sign out", exact: true }).click();
  await p.getByLabel("Workspace account").selectOption("sophia");
  await p.getByRole("button", { name: "Enter workspace", exact: true }).click();
  await expect(
    p.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
  await p
    .getByRole("button", { name: "Toggle navigation", exact: true })
    .click();
  await p.getByRole("button", { name: "Talent", exact: true }).click();
  await expect(
    p
      .getByRole("navigation", { name: "Talent views" })
      .getByRole("button", { name: "Compensation", exact: true }),
  ).toHaveCount(0);
  await expect(
    p.getByText(`Engineering growth ${suffix}`, { exact: true }),
  ).toHaveCount(0);
  await a.close();
});
test("real HTTP enforces tenant, record, role, CSRF, locking and exact revisions", async () => {
  const reviews = (await (
    await hr.get("/api/v1/talent/reviews")
  ).json()) as RecordPage;
  const review = reviews.items.find(
    (x) => "cycle" in x && x.cycle === `Growth cycle ${suffix}`,
  ) as Review;
  expect(
    (await outsider.get(`/api/v1/talent/reviews/${review.meta.id}`)).status(),
  ).toBe(403);
  expect((await worker.get("/api/v1/talent/compensation")).status()).toBe(403);
  expect((await manager.get("/api/v1/talent/succession")).status()).toBe(403);
  expect(
    (
      await worker.post(`/api/v1/talent/reviews/${review.meta.id}/actions`, {
        data: {
          version: review.meta.version,
          action: "GOAL",
          goal: "Forbidden",
          reason: "No CSRF",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(403);
  for (const kind of [
    "requisitions",
    "onboarding",
    "reviews",
    "courses",
    "learning",
    "compensation",
    "succession",
    "scenarios",
  ]) {
    const response = await hr.get(`/api/v1/talent/${kind}`);
    await checkResponse(response, `/api/v1/talent/${kind}`);
  }
  const history = await worker.get(
    `/api/v1/talent/reviews/${review.meta.id}/history`,
  );
  await checkResponse(history, "/api/v1/talent/reviews/{id}/history");
  const intake = await hr.get("/api/v1/payroll/talent-inputs");
  await checkResponse(intake, "/api/v1/payroll/talent-inputs");
  const r = await worker.post(
    `/api/v1/talent/reviews/${review.meta.id}/actions`,
    {
      headers: await headers(worker),
      data: {
        version: review.meta.version,
        action: "GOAL",
        goal: "Locked completion",
        reason: "Denied after completion",
        idempotencyKey: crypto.randomUUID(),
      },
    },
  );
  expect(r.status()).toBe(409);
  const fractional = await hr.post("/api/v1/talent/reviews", {
    headers: await headers(hr),
    data: {
      employeeId: "E003",
      cycle: "Invalid fractional rating",
      from: today,
      through: today,
      minimumRating: 1.5,
      maximumRating: 5,
      reason: "Reject coercion",
      idempotencyKey: crypto.randomUUID(),
    },
  });
  expect(fractional.status()).toBe(400);
  const r2 = await worker.post(
    `/api/v1/talent/reviews/${review.meta.id}/actions`,
    {
      headers: await headers(worker),
      data: {
        version: "1.5",
        action: "GOAL",
        goal: "Fractional revision",
        reason: "Invalid revision",
        idempotencyKey: crypto.randomUUID(),
      },
    },
  );
  expect(r2.status()).toBe(400);
  expect((await r2.json()).fieldErrors).toHaveProperty("version");
});
