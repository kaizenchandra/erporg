import {
  test,
  expect,
  request,
  type APIRequestContext,
} from "@playwright/test";
import { checkResponse } from "./support/contract";
import { writeFileSync } from "node:fs";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
test.setTimeout(60000);
type Run = {
  id: string;
  policyId: string;
  scheduled: boolean;
  status: string;
  processed: number;
  delivered: number;
  skipped: number;
};
type Context = {
  policy: { id: string; version: number } | null;
  state: { status: string };
  runs: Run[];
  schedulerEnabled: boolean;
};
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
async function post(
  c: APIRequestContext,
  path: string,
  data: Record<string, unknown>,
) {
  const r = await c.post(path, {
    headers: await headers(c),
    data: { ...data, idempotencyKey: crypto.randomUUID() },
  });
  expect(r.status(), await r.text()).toBe(200);
  return (await r.json()) as { meta: { id: string; version: number } };
}
test("configured approval reminders deliver persisted in-app notices and expose reconciled item results", async ({
  page,
}) => {
  const hr = await client("amelia"),
    reviewer = await client("olivia"),
    worker = await client("sophia");
  try {
    expect((await worker.get("/api/v1/talent/reminders")).status()).toBe(403);
    const today = new Date().toISOString().slice(0, 10);
    let r = await post(hr, "/api/v1/talent/requisitions", {
      title: `Reminder verification ${Date.now()}`,
      department: "Design",
      location: "London, UK",
      managerId: "E002",
      start: today,
      reason: "Synthetic pending reminder source",
    });
    r = await post(hr, `/api/v1/talent/requisitions/${r.meta.id}/actions`, {
      version: 0,
      action: "SUBMIT",
      reason: "Pending independent review",
    });
    await page.goto(origin);
    await page.getByLabel("Workspace account").selectOption("amelia");
    await page
      .getByRole("button", { name: "Enter workspace", exact: true })
      .click();
    await page.getByRole("button", { name: "Talent", exact: true }).click();
    page.on("dialog", (d) => d.accept());
    await page
      .getByText("Approval reminder administration", { exact: true })
      .click();
    const form = page
      .locator("form")
      .filter({
        has: page.getByRole("button", {
          name: "Save approval reminder policy",
          exact: true,
        }),
      });
    await form.getByRole("checkbox").check();
    await form.getByLabel("Submitted approval age before reminder").fill("0");
    await form.getByLabel("Scan frequency").fill("60");
    await form
      .getByLabel("Reason for this action")
      .fill(
        "Explicit immediate synthetic reminder verification; no business deadline default",
      );
    const [policy] = await Promise.all([
      page.waitForResponse(
        (x) =>
          x.url().endsWith("/reminders/policy") &&
          x.request().method() === "POST",
      ),
      form
        .getByRole("button", { name: "Save approval reminder policy" })
        .click(),
    ]);
    expect(policy.status(), await policy.text()).toBe(200);
    await checkResponse(policy, "/api/v1/talent/reminders/policy", "post");
    const saved = (await policy.json()) as { id: string };
    let context = (await (
      await hr.get("/api/v1/talent/reminders")
    ).json()) as Context;
    if (process.env.TALENT_BACKGROUND_REMINDERS === "1")
      expect(context.schedulerEnabled).toBe(true);
    if (!context.schedulerEnabled) {
      const run = page
        .locator("form")
        .filter({
          has: page.getByRole("button", {
            name: "Deliver due approval reminders",
            exact: true,
          }),
        });
      await run
        .getByLabel("Reason for this action")
        .fill("Deliver one bounded in-app batch");
      await run
        .getByRole("button", { name: "Deliver due approval reminders" })
        .click();
    }
    await expect
      .poll(
        async () => {
          context = (await (
            await hr.get("/api/v1/talent/reminders")
          ).json()) as Context;
          return context.runs.some(
            (run) =>
              run.policyId === saved.id &&
              run.status === "COMMITTED" &&
              run.processed > 0 &&
              (!context.schedulerEnabled || run.scheduled),
          );
        },
        { timeout: 30000, intervals: [1000] },
      )
      .toBe(true);
    const notice = await reviewer.get("/api/v1/talent/notifications");
    expect(notice.status()).toBe(200);
    await checkResponse(notice, "/api/v1/talent/notifications");
    const notices = (await notice.json()) as {
      recordId: string;
      status: string;
    }[];
    expect(
      notices.some(
        (n) => n.recordId === r.meta.id && n.status === "APPROVAL_REMINDER",
      ),
    ).toBe(true);
    const run = context.runs.find(
      (run) =>
        run.policyId === saved.id &&
        run.status === "COMMITTED" &&
        run.processed > 0,
    )!;
    const items = await hr.get(`/api/v1/talent/reminders/runs/${run.id}/items`);
    await checkResponse(items, "/api/v1/talent/reminders/runs/{id}/items");
    expect(
      ((await items.json()) as { recordId: string; status: string }[]).some(
        (i) => i.recordId === r.meta.id && i.status === "DELIVERED",
      ),
    ).toBe(true);
    await page
      .getByRole("button", { name: "Refresh reminder outcomes" })
      .click();
    const batch = page.getByRole("article", {
      name: `Reminder batch ${run.id}`,
      exact: true,
    });
    await expect(batch.getByText(/in-app notices/)).toBeVisible();
    await batch
      .getByText("Inspect reminder item results", { exact: true })
      .click();
    await expect(batch.getByText(new RegExp(r.meta.id))).toContainText(
      "DELIVERED",
    );
    const retry = page
      .locator("form")
      .filter({
        has: page.getByRole("button", {
          name: "Authorize reminder processing retry",
          exact: true,
        }),
      });
    await retry
      .getByLabel("Reason for this action")
      .fill(
        "Inspected successful delivery; authorize safe recheck without duplication",
      );
    await retry
      .getByRole("button", { name: "Authorize reminder processing retry" })
      .click();
    const updated = page
      .locator("form")
      .filter({
        has: page.getByRole("button", {
          name: "Save approval reminder policy",
          exact: true,
        }),
      });
    await updated.getByRole("checkbox").uncheck();
    await updated
      .getByLabel("Reason for this action")
      .fill("Pause synthetic reminder policy after verification");
    await updated
      .getByRole("button", { name: "Save approval reminder policy" })
      .click();
    await expect(page.getByText(/Processing: PAUSED/)).toBeVisible();
    writeFileSync(
      "docs/evidence/talent-reminder-browser-result.json",
      JSON.stringify(
        {
          policyId: saved.id,
          runId: run.id,
          sourceId: r.meta.id,
          automaticSchedulerVerified: context.schedulerEnabled && run.scheduled,
          actualInAppDelivery: true,
          policyPausedAfterVerification: true,
          externalEmailConfigured: false,
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
  }
});
