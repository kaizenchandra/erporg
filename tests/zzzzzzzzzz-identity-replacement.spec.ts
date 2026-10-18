import { test, expect, request, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
const employee = "E-RETURN-TEST",
  path = `/api/v1/employees/${employee}/rehire/access/replacement`,
  endpoint = "/api/v1/employees/{employee}/rehire/access/replacement";
function sql(statement: string) {
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
      "-c",
      statement,
    ],
    { stdio: ["ignore", "pipe", "pipe"], timeout: 15000 },
  );
}
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
  const token = await (await c.get("/api/csrf")).json();
  return { c, headers: { [token.headerName]: token.token } };
}
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/login" &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  expect((await response).status()).toBe(204);
  await expect(page.getByLabel("Workspace account")).toHaveCount(0);
}
async function logout(page: Page) {
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
}
async function profile(page: Page) {
  await page.getByLabel("Search employees").fill(employee);
  await page
    .getByRole("button", { name: "View Jordan Lee", exact: true })
    .click();
}
test("replacement API rejects unsafe identities, scope and snapshots; concurrent independent rejections leave both identities blocked", async () => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    ordinary = await identity("sophia"),
    rival = await identity("marcus"),
    other = await identity("outsider");
  try {
    const response = await hr.c.get(path);
    await checkResponse(response, endpoint, "get");
    const c = await response.json();
    expect(c.canRequest).toBe(true);
    expect(c.candidates).toEqual(["returning-alias"]);
    expect(c.grant.grantNumber).toBe(1);
    const body = {
      subject: "returning-alias",
      reason: "Nominate a different existing login identity",
      version: c.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const session of [ordinary, rival, other]) {
      expect(
        (
          await session.c.get(path, {
            headers: { "X-Role": "HR", "X-Tenant": "atlas" },
          })
        ).status(),
      ).toBe(403);
      expect(
        (
          await session.c.post(path, {
            headers: { ...session.headers, "X-Role": "HR" },
            data: body,
          })
        ).status(),
      ).toBe(403);
    }
    for (const v of [null, 0.5, -1])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...body, version: v },
          })
        ).status(),
      ).toBe(400);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...body, version: 0 },
        })
      ).status(),
    ).toBe(409);
    for (const subject of ["returning", "amelia", "missing"])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...body, subject },
          })
        ).status(),
      ).toBe(400);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...body, reason: " " },
        })
      ).status(),
    ).toBe(400);
    const submitted = await hr.c.post(path, {
      headers: hr.headers,
      data: body,
    });
    await checkResponse(submitted, endpoint, "post");
    const r = await submitted.json();
    expect(
      (
        await (
          await hr.c.post(path, { headers: hr.headers, data: body })
        ).json()
      ).id,
    ).toBe(r.id);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...body, reason: "Changed reason" },
        })
      ).status(),
    ).toBe(409);
    const review = {
      decision: "REJECTED",
      reason: "Independent rejection; keep both identities blocked",
      version: c.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await hr.c.post(path + `/${r.id}/review`, {
          headers: hr.headers,
          data: review,
        })
      ).status(),
    ).toBe(403);
    // Explicit synthetic privilege fixture only, restored before leaving this test.
    sql(
      "update atlas_acceptance.identity_membership set role='HR' where subject='marcus'",
    );
    const results = await Promise.all([
      reviewer.c.post(path + `/${r.id}/review`, {
        headers: reviewer.headers,
        data: review,
      }),
      rival.c.post(path + `/${r.id}/review`, {
        headers: rival.headers,
        data: { ...review, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    await checkResponse(
      results.find((r) => r.status() === 200)!,
      endpoint + "/{id}/review",
      "post",
    );
    const current = await (await hr.c.get(path)).json();
    expect(current.history[0].status).toBe("REJECTED");
    expect(current.accessStatus).toBe("REVOKED");
    expect(current.grant.grantNumber).toBe(1);
    expect(current.canRequest).toBe(true);
  } finally {
    sql(
      "update atlas_acceptance.identity_membership set role='MANAGER' where subject='marcus'",
    );
    await Promise.all(
      [hr, reviewer, ordinary, rival, other].map((s) => s.c.dispose()),
    );
  }
});
test("independent replacement grants fresh Employee access, keeps the old identity held, and the replacement can also be revoked", async ({
  page,
}) => {
  await login(page, "amelia");
  await profile(page);
  const panel = page.getByRole("region", {
      name: "Replacement identity review",
      exact: true,
    }),
    access = page.getByRole("region", {
      name: "Current rehire local access",
      exact: true,
    });
  await panel
    .getByRole("button", { name: "Request replacement identity", exact: true })
    .click();
  await panel
    .getByRole("button", { name: "Submit replacement request", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByLabel("Replacement identity (required)", { exact: true })
    .selectOption("returning-alias");
  await panel
    .getByLabel("Replacement reason (required)", { exact: true })
    .fill(
      "Identity operations verified this different mapped account; old identity remains suspended",
    );
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    panel.getByLabel("Replacement reason (required)", { exact: true }),
  ).toHaveValue(
    "Identity operations verified this different mapped account; old identity remains suspended",
  );
  await panel
    .getByRole("button", { name: "Submit replacement request", exact: true })
    .click();
  await expect(panel).toContainText("SUBMITTED");
  await expect(panel).toContainText("Another HR employee must review");
  await expect(access).toContainText("REVOKED");
  await expect(
    panel.getByRole("button", {
      name: "Review replacement identity",
      exact: true,
    }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await logout(page);
  await login(page, "olivia");
  await profile(page);
  await panel
    .getByRole("button", { name: "Review replacement identity", exact: true })
    .click();
  await expect(panel).toContainText("returning stays held");
  await panel
    .getByLabel("Replacement reason (required)", { exact: true })
    .fill("Independent HR confirms different identity and Employee-only scope");
  const sent = page.waitForRequest(
      (r) =>
        r.method() === "POST" &&
        r.url().endsWith("/review") &&
        r.url().includes("/access/replacement/"),
    ),
    outcome = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" &&
        r.url().endsWith("/review") &&
        r.url().includes("/access/replacement/"),
    );
  await panel
    .getByRole("button", { name: "Record replacement decision", exact: true })
    .click();
  const command = (await sent).postDataJSON() as {
    decision: string;
    reason: string;
    version: number;
    idempotencyKey: string;
  };
  const approved = await outcome;
  await checkResponse(approved, endpoint + "/{id}/review", "post");
  const result = await approved.json();
  await expect(panel).toContainText("APPROVED");
  await expect(access).toContainText("GRANTED · returning-alias");
  const csrf = await (await page.request.get("/api/csrf")).json();
  expect(
    (
      await page.request.post(path + `/${result.id}/review`, {
        headers: { [csrf.headerName]: csrf.token },
        data: command,
      })
    ).status(),
  ).toBe(200);
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await logout(page);
  await login(page, "returning-alias");
  expect((await (await page.request.get("/api/v1/session")).json()).role).toBe(
    "EMPLOYEE",
  );
  expect(
    (await (await page.request.get("/api/v1/directory")).json()).total,
  ).toBe(1);
  expect(
    (
      await page.request.get("/api/v1/employees/E001", {
        headers: { "X-Role": "HR" },
      })
    ).status(),
  ).toBe(403);
  expect((await page.request.get(path)).status()).toBe(403);
  const held = await identity("returning-alias");
  try {
    const denied = await request.newContext({
      baseURL: "http://127.0.0.1:3000",
    });
    try {
      const t = await (await denied.get("/api/csrf")).json();
      expect(
        (
          await denied.post("/api/login", {
            form: { username: "returning", password: "atlas-demo" },
            headers: { [t.headerName]: t.token },
          })
        ).status(),
      ).toBe(401);
    } finally {
      await denied.dispose();
    }
    await logout(page);
    await login(page, "amelia");
    await profile(page);
    await access
      .getByRole("button", { name: "Revoke local access", exact: true })
      .click();
    await access
      .getByLabel("Revocation reason (required)", { exact: true })
      .fill(
        "Suspend the replacement account after the demonstration access review",
      );
    await access
      .getByRole("button", {
        name: "Confirm immediate revocation",
        exact: true,
      })
      .click();
    await expect(access).toContainText("REVOKED · returning-alias");
    await expect(panel).toContainText("One replacement has been used");
    await expect(
      panel.getByRole("button", {
        name: "Request replacement identity",
        exact: true,
      }),
    ).toHaveCount(0);
    expect((await held.c.get("/api/v1/session")).status()).toBe(401);
    for (const user of ["returning", "returning-alias"]) {
      const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
      try {
        const t = await (await c.get("/api/csrf")).json();
        expect(
          (
            await c.post("/api/login", {
              form: { username: user, password: "atlas-demo" },
              headers: { [t.headerName]: t.token },
            })
          ).status(),
        ).toBe(401);
      } finally {
        await c.dispose();
      }
    }
    const c = await (
      await page.request.get(`/api/v1/employees/${employee}/rehire/access`)
    ).json();
    const retained = await (
      await page.request.get(
        `/api/v1/employees/${employee}/rehire/access/replacement`,
      )
    ).json();
    expect(retained.grants.map((g: { subject: string }) => g.subject)).toEqual([
      "returning",
      "returning-alias",
    ]);
    expect(
      retained.revocations.map((r: { subject: string }) => r.subject),
    ).toEqual(["returning", "returning-alias"]);
    await panel
      .getByText("Access grants and revocations", { exact: true })
      .click();
    await expect(panel).toContainText("Revoked identity · returning");
    await expect(panel).toContainText(
      "Suspend the replacement account after the demonstration access review",
    );
    expect(c.grantNumber).toBe(2);
    expect(c.subject).toBe("returning-alias");
    expect(c.employmentStatus).toBe("Active");
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
    await panel
      .getByRole("heading", { name: "Replace a revoked identity", exact: true })
      .scrollIntoViewIfNeeded();
    await page.screenshot({
      path: "docs/evidence/identity-replacement-mobile.png",
    });
  } finally {
    await held.c.dispose();
  }
});
