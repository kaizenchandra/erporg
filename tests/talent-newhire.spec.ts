import {
  test,
  expect,
  request,
  type APIRequestContext,
  type Page,
} from "@playwright/test";
import { readFileSync, writeFileSync } from "node:fs";
import { checkResponse } from "./support/contract";
import type { Requisition, RecordPage } from "../features/talent/contracts";
const origin = process.env.ATLAS_BROWSER_ORIGIN ?? "http://127.0.0.1:3000";
test.use({ baseURL: origin });
test.setTimeout(90000);
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
async function signIn(p: Page, user: string) {
  await p.goto(origin);
  await p.getByLabel("Workspace account").selectOption(user);
  await p.getByRole("button", { name: "Enter workspace", exact: true }).click();
  await p.getByRole("button", { name: "Talent", exact: true }).click();
  p.on("dialog", (d) => d.accept());
}
test("new hire receives a persisted independently reviewed exact provider binding", async ({
  browser,
}) => {
  test.skip(
    process.env.TALENT_IDENTITY_SETUP !== "1",
    "Explicit local identity-provider fixture required",
  );
  const fixture = JSON.parse(
    readFileSync("docs/evidence/talent-newhire-provider-fixture.json", "utf8"),
  ) as { issuer: string; username: string; subject: string };
  const hr = await client("amelia"),
    reviewer = await client("olivia");
  try {
    const today = new Date().toISOString().slice(0, 10);
    let r = await post<Requisition>(hr, "/api/v1/talent/requisitions", {
      title: `SSO joining ${fixture.username}`,
      department: "Design",
      location: "London, UK",
      managerId: "E002",
      start: today,
      reason: "Synthetic new-hire identity verification",
    });
    r = await post<Requisition>(
      hr,
      `/api/v1/talent/requisitions/${r.meta.id}/actions`,
      {
        version: r.meta.version,
        action: "SUBMIT",
        reason: "Verified test vacancy",
      },
    );
    r = await post<Requisition>(
      reviewer,
      `/api/v1/talent/requisitions/${r.meta.id}/actions`,
      {
        version: r.meta.version,
        action: "APPROVE",
        reason: "Independent test vacancy review",
      },
    );
    let candidateId: string | null = null;
    for (const action of [
      "ADD",
      "INTERVIEW",
      "OFFER",
      "ACCEPTED",
      "SEND_HIRE",
      "APPROVE_HIRE",
    ]) {
      r = await post<Requisition>(
        action === "APPROVE_HIRE" ? reviewer : hr,
        `/api/v1/talent/requisitions/${r.meta.id}/candidates`,
        {
          version: r.meta.version,
          candidateId,
          action,
          name: "Newhire Synthetic",
          email: fixture.username + "@example.test",
          phone: "+12025550192",
          interviewAt: today + "T10:00:00Z",
          notes: "Synthetic interview plan",
          offer: "Synthetic approved employment; no payment",
          reason: "Reviewed synthetic hiring step",
        },
      );
      candidateId = r.candidates[0].id;
    }
    const employee = r.candidates[0].employeeId;
    const plans = (await (
      await hr.get(`/api/v1/talent/onboarding?employee=${employee}`)
    ).json()) as RecordPage;
    const plan = plans.items[0];
    const a = await browser.newContext(),
      p = await a.newPage();
    await signIn(p, "amelia");
    await p.getByRole("button", { name: "Onboarding", exact: true }).click();
    await p
      .getByRole("button", {
        name: `Open Joining plan · ${employee}`,
        exact: true,
      })
      .click();
    const section = p.getByRole("region", {
      name: "Reviewed new-hire SSO binding",
    });
    await section.getByLabel("Verified provider subject").fill(fixture.subject);
    const f = section.locator("form").first();
    await f
      .getByLabel("Reason for this action")
      .fill("Verified exact sub from local provider admin API");
    const [submitted] = await Promise.all([
      p.waitForResponse(
        (x) =>
          x.url().endsWith(`/initial-identities/employees/${employee}`) &&
          x.request().method() === "POST",
      ),
      f
        .getByRole("button", { name: "Submit verified identity binding" })
        .click(),
    ]);
    expect(submitted.status(), await submitted.text()).toBe(200);
    await checkResponse(
      submitted,
      "/api/v1/initial-identities/employees/{employee}",
      "post",
    );
    await expect(
      section.getByText("Awaiting independent review", { exact: false }),
    ).toBeVisible();
    await a.close();
    const b = await browser.newContext(),
      q = await b.newPage();
    await signIn(q, "olivia");
    await q.getByRole("button", { name: "Onboarding", exact: true }).click();
    await q
      .getByRole("button", {
        name: `Open Joining plan · ${employee}`,
        exact: true,
      })
      .click();
    const review = q.getByRole("region", {
      name: "Reviewed new-hire SSO binding",
    });
    const decision = review
      .locator("form")
      .filter({
        has: q.getByRole("button", { name: "Record identity review" }),
      });
    await decision
      .getByLabel("Reason for this action")
      .fill(
        "Independent verification of provider subject and approved new hire",
      );
    await decision
      .getByRole("button", { name: "Record identity review" })
      .click();
    await expect(
      review.getByText(`${fixture.subject} · APPROVE`, { exact: true }),
    ).toBeVisible();
    await b.close();
    const context = await hr.get(
      `/api/v1/initial-identities/employees/${employee}`,
    );
    await checkResponse(
      context,
      "/api/v1/initial-identities/employees/{employee}",
    );
    writeFileSync(
      "docs/evidence/talent-newhire-application-fixture.json",
      JSON.stringify(
        {
          ...fixture,
          employee,
          joiningId: plan.meta.id,
          bindingApproved: true,
          developmentOnly: true,
        },
        null,
        2,
      ) + "\n",
    );
  } finally {
    await hr.dispose();
    await reviewer.dispose();
  }
});
test("the newly hired employee signs in through real OIDC and completes their own joining form", async ({
  page,
}) => {
  test.skip(
    process.env.TALENT_NEW_HIRE_OIDC !== "1",
    "Run after local setup with the oidc-demo backend",
  );
  const f = JSON.parse(
    readFileSync(
      "docs/evidence/talent-newhire-application-fixture.json",
      "utf8",
    ),
  ) as {
    username: string;
    subject: string;
    employee: string;
    joiningId: string;
  };
  await page.goto(origin);
  await page.getByRole("link", { name: "Sign in securely" }).click();
  await page.locator("#username").fill(f.username);
  await page.locator("#password").fill("atlas-demo");
  await page.locator("#kc-login").click();
  await page.waitForURL(origin + "/");
  const session = await (await page.request.get("/api/v1/session")).json();
  expect(session.subject).toBe(f.subject);
  expect(session.employeeId).toBe(f.employee);
  expect(session.role).toBe("EMPLOYEE");
  expect((await page.request.get("/api/v1/talent/compensation")).status()).toBe(
    403,
  );
  expect(
    (
      await page.request.get(
        "/api/v1/initial-identities/employees/" + f.employee,
      )
    ).status(),
  ).toBe(403);
  await page.getByRole("button", { name: "Talent", exact: true }).click();
  await page.getByRole("button", { name: "Onboarding", exact: true }).click();
  await page
    .getByRole("button", {
      name: `Open Joining plan · ${f.employee}`,
      exact: true,
    })
    .click();
  await page.getByText("Complete joining form", { exact: true }).click();
  await page
    .getByLabel("Joining information and acknowledgements")
    .fill(
      "New employee acknowledged joining information through their own verified SSO identity.",
    );
  const form = page
    .locator("form")
    .filter({
      has: page.getByRole("button", { name: "Save joining form", exact: true }),
    });
  await form
    .getByLabel("Reason for this action")
    .fill("New-hire self-service via real local OIDC");
  await form
    .getByRole("button", { name: "Save joining form", exact: true })
    .click();
  await expect(
    page.getByText(
      "Joining form: New employee acknowledged joining information through their own verified SSO identity.",
    ),
  ).toBeVisible();
  await page.reload();
  await page.getByRole("button", { name: "Talent", exact: true }).click();
  const own = await page.request.get(
    `/api/v1/talent/onboarding/${f.joiningId}`,
  );
  expect(own.status()).toBe(200);
  expect((await own.json()).joiningForm).toContain("own verified SSO");
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "Sign in securely" }),
  ).toBeVisible();
  expect((await page.request.get("/api/v1/session")).status()).toBe(401);
});
