import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
}
async function signOut(page: Page) {
  if (await page.getByRole("dialog").count())
    await page.getByRole("button", { name: "Close panel" }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Enter workspace" }),
  ).toBeVisible();
}
async function identity(user: string) {
  const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  let t = await (await c.get("/api/csrf")).json();
  expect(
    (
      await c.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [t.headerName]: t.token },
      })
    ).status(),
  ).toBe(204);
  t = await (await c.get("/api/csrf")).json();
  return { c, headers: { [t.headerName]: t.token } };
}
test("manager requests reporting change, HR approves, old manager loses access and HR restores fixture", async ({
  page,
}) => {
  await signIn(page, "marcus");
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("managerId");
  await page.getByLabel("Find a manager").fill("Amelia");
  await page.getByLabel("New value").selectOption("E001");
  await page
    .getByLabel("Reason for change")
    .fill("Synthetic reporting realignment");
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.screenshot({
    path: "docs/evidence/reporting-change-form.png",
    fullPage: true,
  });
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "amelia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: "Reporting manager" })
    .filter({ hasText: "Sophia Martinez" })
    .getByRole("button", { name: "View request" })
    .first()
    .click();
  await page
    .getByLabel("Decision comment")
    .fill("HR reviewed team access impact");
  await page.getByRole("button", { name: "Confirm approval" }).click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "marcus");
  expect((await page.request.get("/api/v1/employees/E003")).status()).toBe(403);
  await page.getByLabel("Search employees").fill("Sophia");
  await expect(
    page.getByRole("button", { name: "View Sophia Martinez", exact: true }),
  ).toHaveCount(0);
  await signOut(page);
  await signIn(page, "sophia");
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await expect(
    page
      .locator(".detail-grid > div")
      .filter({ has: page.locator("dt", { hasText: "Reporting manager" }) })
      .locator("dd"),
  ).toHaveText("E001");
  await expect(
    page
      .locator(".history-event")
      .filter({ hasText: "Reporting manager" })
      .filter({ hasText: "approved" })
      .first(),
  ).toBeVisible();
  await page.screenshot({
    path: "docs/evidence/reporting-change-profile.png",
    fullPage: true,
  });
  const hr = await identity("amelia"),
    reviewer = await identity("olivia");
  try {
    const employee = await (await hr.c.get("/api/v1/employees/E003")).json();
    const r = await hr.c.post("/api/v1/requests", {
      headers: hr.headers,
      data: {
        employeeId: "E003",
        field: "managerId",
        newValue: "E002",
        effectiveDate: new Date().toISOString().slice(0, 10),
        reason: "Restore reporting fixture",
        version: employee.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(r.status()).toBe(200);
    const change = await r.json();
    expect(
      (
        await reviewer.c.post(`/api/v1/requests/${change.id}/decision`, {
          headers: reviewer.headers,
          data: {
            decision: "APPROVED",
            comment: "Restore independently verified",
          },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await hr.c.dispose();
    await reviewer.c.dispose();
  }
});
test("concurrent reciprocal reporting approvals cannot create a cycle", async () => {
  const first = await identity("amelia"),
    second = await identity("olivia"),
    manager = await identity("marcus"),
    employee = await identity("sophia");
  const date = new Date().toISOString().slice(0, 10);
  try {
    await checkResponse(
      await manager.c.get("/api/v1/reporting/managers?size=1"),
      "/api/v1/reporting/managers",
    );
    expect((await employee.c.get("/api/v1/reporting/managers")).status()).toBe(
      403,
    );
    expect(
      (await manager.c.get("/api/v1/reporting/managers?size=101")).status(),
    ).toBe(400);
    const a = await (await first.c.get("/api/v1/employees/E002")).json(),
      b = await (await second.c.get("/api/v1/employees/E005")).json();
    async function submit(
      who: typeof first,
      target: string,
      value: string,
      version: number,
    ) {
      const r = await who.c.post("/api/v1/requests", {
        headers: who.headers,
        data: {
          employeeId: target,
          field: "managerId",
          newValue: value,
          effectiveDate: date,
          reason: "Concurrent hierarchy fixture",
          version,
          idempotencyKey: crypto.randomUUID(),
        },
      });
      expect(r.status()).toBe(200);
      return r.json();
    }
    const ra = await submit(first, "E002", "E005", a.version),
      rb = await submit(second, "E005", "E002", b.version);
    const results = await Promise.all([
      second.c.post(`/api/v1/requests/${ra.id}/decision`, {
        headers: second.headers,
        data: {
          decision: "APPROVED",
          comment: "Independent hierarchy review A",
        },
      }),
      first.c.post(`/api/v1/requests/${rb.id}/decision`, {
        headers: first.headers,
        data: {
          decision: "APPROVED",
          comment: "Independent hierarchy review B",
        },
      }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 400]);
    const currentA = await (await first.c.get("/api/v1/employees/E002")).json(),
      currentB = await (await first.c.get("/api/v1/employees/E005")).json();
    expect(currentA.managerId === "E005" && currentB.managerId === "E002").toBe(
      false,
    );
    for (const [i, who, reviewer, target, original] of [
      [0, first, second, "E002", a],
      [1, second, first, "E005", b],
    ] as const) {
      if (results[i].status() === 400) {
        expect(
          (
            await reviewer.c.post(
              `/api/v1/requests/${i === 0 ? ra.id : rb.id}/decision`,
              {
                headers: reviewer.headers,
                data: {
                  decision: "REJECTED",
                  comment: "Cycle prevented; end fixture",
                },
              },
            )
          ).status(),
        ).toBe(200);
      } else {
        const current = await (
          await who.c.get(`/api/v1/employees/${target}`)
        ).json();
        const restore = await submit(
          who,
          target,
          original.managerId,
          current.version,
        );
        expect(
          (
            await reviewer.c.post(`/api/v1/requests/${restore.id}/decision`, {
              headers: reviewer.headers,
              data: {
                decision: "APPROVED",
                comment: "Restore original reporting line",
              },
            })
          ).status(),
        ).toBe(200);
      }
    }
  } finally {
    await Promise.all([
      first.c.dispose(),
      second.c.dispose(),
      manager.c.dispose(),
      employee.c.dispose(),
    ]);
  }
});
