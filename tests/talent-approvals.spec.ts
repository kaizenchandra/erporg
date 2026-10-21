import {
  test,
  expect,
  request,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { checkResponse } from "./support/contract";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
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
  return (await r.json()) as {
    meta: { id: string; version: number; status: string };
  };
}
async function signIn(p: Page, user: string) {
  await p.goto(origin);
  await p.getByLabel("Workspace account").selectOption(user);
  await p.getByRole("button", { name: "Enter workspace", exact: true }).click();
  await p.getByRole("button", { name: "Talent", exact: true }).click();
  p.on("dialog", (d) => d.accept());
}
test("manager delegates a pending review to an already-authorized HR reviewer through the approval inbox", async ({
  browser,
  page,
}) => {
  const manager = await client("marcus"),
    worker = await client("sophia");
  const b = await browser.newContext(),
    reviewer = await b.newPage();
  try {
    const date = new Date().toISOString().slice(0, 10),
      cycle = `Delegated review ${Date.now()}`;
    let r = await post(manager, "/api/v1/talent/reviews", {
      employeeId: "E003",
      cycle,
      from: date,
      through: date,
      minimumRating: 1,
      maximumRating: 5,
      reason: "Configured synthetic review",
    });
    r = await post(worker, `/api/v1/talent/reviews/${r.meta.id}/actions`, {
      version: 0,
      action: "GOAL",
      goal: "Deliver verified work",
      reason: "Own goal",
    });
    r = await post(worker, `/api/v1/talent/reviews/${r.meta.id}/actions`, {
      version: r.meta.version,
      action: "SUBMIT",
      selfReview: "Completed with evidence",
      reason: "Employee submission",
    });
    await signIn(page, "marcus");
    await page.getByText("Talent approval inbox", { exact: true }).click();
    const task = page.locator("article").filter({
      has: page.getByRole("heading", {
        name: `${cycle} · E003`,
        exact: true,
      }),
    });
    await task
      .getByText("Delegate this approval responsibility", { exact: true })
      .click();
    await task
      .getByRole("combobox", { name: "Authorized HR reviewer" })
      .selectOption("E005");
    await task
      .getByLabel("Reason for this action")
      .fill("Manager assigns responsibility to independently authorized HR");
    const [delegated] = await Promise.all([
      page.waitForResponse(
        (x) =>
          x.url().endsWith("/approvals/delegations") &&
          x.request().method() === "POST",
      ),
      task
        .getByRole("button", { name: "Assign approval responsibility" })
        .click(),
    ]);
    expect(delegated.status(), await delegated.text()).toBe(200);
    await checkResponse(
      delegated,
      "/api/v1/talent/approvals/delegations",
      "post",
    );
    await expect(
      task.getByText(/Responsibility assigned by E002 to E005/),
    ).toBeVisible();
    expect((await manager.get("/api/v1/employees/E005")).status()).toBe(403);
    await signIn(reviewer, "olivia");
    await reviewer.getByText("Talent approval inbox", { exact: true }).click();
    const assigned = reviewer.locator("article").filter({
      has: reviewer.getByRole("heading", {
        name: `${cycle} · E003`,
        exact: true,
      }),
    });
    await expect(assigned).toContainText("Assigned to you");
    await assigned
      .getByRole("button", { name: `Review ${cycle} · E003`, exact: true })
      .click();
    const f = reviewer.locator("form").filter({
      has: reviewer.getByRole("button", {
        name: "Record review decision",
        exact: true,
      }),
    });
    await f.getByLabel("Final rating", { exact: false }).fill("4");
    await f
      .getByLabel("Reason for this action")
      .fill("Independent HR review after recorded delegation");
    await f
      .getByRole("button", { name: "Record review decision", exact: true })
      .click();
    await expect(
      reviewer
        .getByRole("region", { name: "Talent record detail" })
        .getByText("COMPLETED", { exact: true }),
    ).toBeVisible();
    await reviewer
      .getByText("Approval responsibility history", { exact: true })
      .click();
    await expect(reviewer.getByText(/E002 → E005/)).toBeVisible();
    const history = await manager.get(
      `/api/v1/talent/approvals/history?kind=reviews&id=${r.meta.id}`,
    );
    expect(history.status()).toBe(200);
    await checkResponse(history, "/api/v1/talent/approvals/history");
    expect((await history.json()).length).toBe(1);
  } finally {
    await manager.dispose();
    await worker.dispose();
    await b.close();
  }
});
