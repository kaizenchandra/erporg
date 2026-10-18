import { test, expect, request, type Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
const employee = "E-RETURN-TEST",
  path = `/api/v1/employees/${employee}/rehire/activation`,
  endpoint = "/api/v1/employees/{employee}/rehire/activation";
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
    ],
    { input: statement, stdio: ["pipe", "pipe", "pipe"], timeout: 15000 },
  );
}
test.beforeAll(async () => {
  // Historical end is explicit synthetic setup. Rehire itself uses the real reviewed API.
  let fixture = readFileSync("tests/fixtures/former-employee.sql", "utf8")
    .replaceAll("E-REHIRE-TEST", employee)
    .replaceAll("former-", "returning-former-")
    .replaceAll("rehire-returning-former-resignation", "returning-resignation")
    .replaceAll("rehire-returning-former-test", "returning")
    .replaceAll("Alex Morgan", "Jordan Lee")
    .replaceAll("alex.rehire@example.test", "jordan.returning@example.test");
  fixture = fixture.replace(
    "commit;",
    "insert into identity_membership(subject,tenant,employee_id,name,role) values('returning-alias','atlas','E-RETURN-TEST','Jordan Lee alias','HR');\ncommit;",
  );
  sql(fixture);
  const hr = await identity("amelia"),
    reviewer = await identity("olivia");
  try {
    const p = await (await hr.c.get(`/api/v1/employees/${employee}`)).json();
    const r = await hr.c.post(`/api/v1/employees/${employee}/rehire`, {
      headers: hr.headers,
      data: {
        startDate: new Date().toISOString().slice(0, 10),
        title: "Returning engineer",
        department: "Engineering",
        location: "London",
        managerId: "E002",
        reason: "Synthetic rehire into current onboarding",
        version: p.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(r.status()).toBe(200);
    const rehire = await r.json();
    expect(
      (
        await reviewer.c.post(
          `/api/v1/employees/${employee}/rehire/${rehire.id}/review`,
          {
            headers: reviewer.headers,
            data: {
              decision: "APPROVED",
              reason: "Independent rehire",
              version: p.version,
              idempotencyKey: crypto.randomUUID(),
            },
          },
        )
      ).status(),
    ).toBe(200);
  } finally {
    await hr.c.dispose();
    await reviewer.c.dispose();
  }
});
test("completion/access contracts reject escalation and unsafe inputs; competing independent rejections leave onboarding blocked", async () => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    rival = await identity("olivia"),
    ordinary = await identity("sophia"),
    outsider = await identity("outsider");
  try {
    const ctx = (await checkResponse(await hr.c.get(path), endpoint)) as {
      employeeVersion: number;
      completion: unknown;
      identities: string[];
    };
    expect(ctx.completion).toBeNull();
    expect(ctx.identities).toContain("returning");
    const c = {
      subject: "returning",
      reason: "Attest onboarding and nominate one Employee identity",
      version: ctx.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const u of [ordinary, outsider]) {
      expect((await u.c.get(path)).status()).toBe(403);
      expect(
        (
          await u.c.post(path, {
            headers: { ...u.headers, "X-Role": "HR", "X-Tenant-ID": "atlas" },
            data: c,
          })
        ).status(),
      ).toBe(403);
    }
    for (const version of [null, -1, 0.5])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...c, version },
          })
        ).status(),
      ).toBe(400);
    for (const subject of [
      null,
      "",
      "amelia",
      "outsider",
      "unconfigured-subject",
    ])
      expect(
        (
          await hr.c.post(path, {
            headers: hr.headers,
            data: { ...c, subject },
          })
        ).status(),
      ).toBe(400);
    const r = (await checkResponse(
      await hr.c.post(path, { headers: hr.headers, data: c }),
      endpoint,
      "post",
    )) as { id: string };
    expect(
      (await (await hr.c.post(path, { headers: hr.headers, data: c })).json())
        .id,
    ).toBe(r.id);
    expect(
      (
        await hr.c.post(path, {
          headers: hr.headers,
          data: { ...c, subject: "returning-alias" },
        })
      ).status(),
    ).toBe(409);
    const review = {
        decision: "REJECTED",
        reason: "Independent rejection keeps access blocked",
        version: c.version,
        idempotencyKey: crypto.randomUUID(),
      },
      reviewPath = `${path}/${r.id}/review`;
    expect(
      (
        await hr.c.post(reviewPath, { headers: hr.headers, data: review })
      ).status(),
    ).toBe(403);
    const other = { ...review, idempotencyKey: crypto.randomUUID() };
    const responses = await Promise.all([
      reviewer.c.post(reviewPath, { headers: reviewer.headers, data: review }),
      rival.c.post(reviewPath, { headers: rival.headers, data: other }),
    ]);
    expect(responses.map((r) => r.status()).sort()).toEqual([200, 409]);
    await checkResponse(
      responses.find((r) => r.status() === 200)!,
      endpoint + "/{id}/review",
      "post",
    );
    expect((await (await hr.c.get(path)).json()).completion).toBeNull();
  } finally {
    await Promise.all(
      [hr, reviewer, rival, ordinary, outsider].map((x) => x.c.dispose()),
    );
  }
});
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByLabel("Workspace account")).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
}
async function profile(page: Page) {
  await page.getByLabel("Search employees").fill(employee);
  await page
    .getByRole("button", { name: "View Jordan Lee", exact: true })
    .click();
}
test("independent completion grants one fresh Employee login, refreshes Active state and keeps other identities blocked", async ({
  page,
}) => {
  await login(page, "amelia");
  await profile(page);
  const panel = page.getByRole("region", {
    name: "Rehire completion and fresh access",
    exact: true,
  });
  await panel
    .getByRole("button", {
      name: "Request onboarding completion and access",
      exact: true,
    })
    .click();
  await panel
    .getByRole("button", {
      name: "Submit completion/access request",
      exact: true,
    })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByLabel("Identity for fresh Employee access (required)", {
      exact: true,
    })
    .selectOption("returning");
  await panel
    .getByLabel("Completion/access reason (required)", { exact: true })
    .fill("Onboarding evidence checked; nominate one least-privilege identity");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    panel.getByLabel("Completion/access reason (required)", { exact: true }),
  ).toHaveValue(
    "Onboarding evidence checked; nominate one least-privilege identity",
  );
  await panel
    .getByRole("button", {
      name: "Submit completion/access request",
      exact: true,
    })
    .click();
  await expect(panel).toContainText("SUBMITTED");
  await expect(panel).toContainText("Another HR employee must review");
  await expect(
    panel.getByRole("button", {
      name: "Review onboarding and access",
      exact: true,
    }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await login(page, "olivia");
  await profile(page);
  await panel
    .getByRole("button", { name: "Review onboarding and access", exact: true })
    .click();
  await expect(panel).toContainText(
    "grant returning fresh Employee-only local access",
  );
  await panel
    .getByLabel("Completion/access reason (required)", { exact: true })
    .fill(
      "Independent HR approves completion and fresh Employee-only local policy",
    );
  await panel
    .getByRole("button", {
      name: "Record completion/access decision",
      exact: true,
    })
    .click();
  await expect(panel).toContainText(
    "Original local Employee grant for returning",
  );
  await expect(panel).toContainText(
    "The original grant did not restore HR/manager privilege",
  );
  await expect(
    page.getByRole("region", {
      name: "Employment episodes and rehire",
      exact: true,
    }),
  ).toContainText("Episode 2 · ACTIVE");
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
    .getByText("The original grant did not restore HR/manager privilege", {
      exact: false,
    })
    .scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/rehire-activation-mobile.png",
  });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await login(page, "returning");
  expect((await (await page.request.get("/api/v1/session")).json()).role).toBe(
    "EMPLOYEE",
  );
  expect(
    (
      await page.request.get("/api/v1/employees/E001", {
        headers: { "X-Role": "HR" },
      })
    ).status(),
  ).toBe(403);
  expect((await page.request.get(path)).status()).toBe(403);
  expect(
    (await (await page.request.get("/api/v1/directory")).json()).total,
  ).toBe(1);
  const alias = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  try {
    const t = await (await alias.get("/api/csrf")).json();
    expect(
      (
        await alias.post("/api/login", {
          form: { username: "returning-alias", password: "atlas-demo" },
          headers: { [t.headerName]: t.token },
        })
      ).status(),
    ).toBe(401);
  } finally {
    await alias.dispose();
  }
});
test("an unstamped existing session returns to the correct local sign-in screen and can authenticate freshly", async ({
  page,
}) => {
  await login(page, "amelia");
  sql(
    "set search_path=atlas_acceptance; delete from spring_session_attributes a using spring_session s where a.session_primary_id=s.primary_id and s.principal_name='amelia' and a.attribute_name='atlas.workforceEpoch';",
  );
  await page.reload();
  await expect(
    page.getByRole("button", { name: "Enter workspace", exact: true }),
  ).toBeVisible();
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByLabel("Workspace account")).toHaveCount(0);
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
});
