import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { Employee } from "@/lib/contracts";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
async function signOut(page: Page) {
  if (await page.getByRole("dialog").count())
    await page
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
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
  const csrf = await (await c.get("/api/csrf")).json();
  return { c, headers: { [csrf.headerName]: csrf.token } };
}
test("employee personal email approval and explicit removal preserve work identity with uncertain-response recovery and mobile privacy", async ({
  page,
}) => {
  const supplied = "Person.AV+contact@Example.test";
  await signIn(page, "sophia");
  const before = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as Employee;
  expect(before.personalEmail).toBe("");
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("personalEmail");
  await expect(
    page.getByText("This records supplied private contact data", {
      exact: false,
    }),
  ).toBeVisible();
  await page.getByLabel("New value").fill("invalid..mail@example.test");
  await page
    .getByLabel("Reason for change")
    .fill("Employee supplied private personal email");
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("New value")).toHaveValue(
    "invalid..mail@example.test",
  );
  page.once("dialog", (dialog) => dialog.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByLabel("Reason for change")).toHaveValue(
    "Employee supplied private personal email",
  );
  await page.getByLabel("New value").fill(supplied);
  await page.route(
    "**/api/v1/requests",
    async (route) => {
      if (route.request().method() !== "POST") {
        await route.continue();
        return;
      }
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      await route.abort("failed");
    },
    { times: 1 },
  );
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("New value")).toHaveValue(supplied);
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  const requests = await (await page.request.get("/api/v1/requests")).json();
  expect(
    requests.filter(
      (r: { field: string; newValue: string }) =>
        r.field === "personalEmail" && r.newValue === supplied,
    ),
  ).toHaveLength(1);
  await signOut(page);
  await signIn(page, "amelia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: supplied })
    .getByRole("button", { name: "View request" })
    .click();
  await expect(page.getByRole("dialog")).toContainText("Personal email");
  await page
    .getByLabel("Decision comment")
    .fill("Independent supplied contact review; no mailbox verification");
  await page
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "sophia");
  const after = (await checkResponse(
    await page.request.get("/api/v1/employees/E003"),
    "/api/v1/employees/{id}",
  )) as Employee;
  expect(after).toMatchObject({
    personalEmail: supplied,
    email: before.email,
    name: before.name,
    id: before.id,
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Open your profile", exact: true })
    .click();
  await expect(
    page.getByRole("dialog").getByText(supplied, { exact: true }).first(),
  ).toBeVisible();
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
  await page.screenshot({ path: "docs/evidence/personal-email-mobile.png" });
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("personalEmail");
  await page
    .getByRole("checkbox", { name: "Remove the current value" })
    .check();
  await expect(page.getByLabel("New value")).toBeDisabled();
  await page
    .getByLabel("Reason for change")
    .fill("Explicit personal contact removal");
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  const own = await (await page.request.get("/api/v1/requests")).json();
  const removal = own.find(
    (r: { field: string; newValue: string; status: string }) =>
      r.field === "personalEmail" &&
      r.newValue === "" &&
      r.status === "SUBMITTED",
  );
  expect(removal).toBeTruthy();
  const hr = await identity("amelia");
  try {
    expect(
      (
        await hr.c.post(`/api/v1/requests/${removal.id}/decision`, {
          headers: hr.headers,
          data: {
            decision: "APPROVED",
            comment: "Independent explicit removal",
          },
        })
      ).status(),
    ).toBe(200);
  } finally {
    await hr.c.dispose();
  }
  expect(
    (await (await page.request.get("/api/v1/employees/E003")).json())
      .personalEmail,
  ).toBe("");
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await signIn(page, "marcus");
  await page.getByLabel("Search employees").fill("E003");
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await expect(page.getByRole("dialog")).not.toContainText(supplied);
  expect(
    (await (await page.request.get("/api/v1/employees/E003")).json())
      .personalEmail,
  ).toBeNull();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await expect(
    page
      .getByLabel("Information to change")
      .locator('option[value="personalEmail"]'),
  ).toHaveCount(0);
});
test("personal email APIs enforce tenant and field privacy, replay, concurrent review and future-date boundaries", async () => {
  const self = await identity("sophia"),
    hr = await identity("amelia"),
    manager = await identity("marcus"),
    outside = await identity("outsider");
  try {
    const e = (await (
      await self.c.get("/api/v1/employees/E003")
    ).json()) as Employee;
    const command = {
      employeeId: e.id,
      field: "personalEmail",
      newValue: "api-personal@example.test",
      effectiveDate: new Date().toISOString().slice(0, 10),
      reason: "Synthetic private contact API",
      version: e.version,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const value of [
      null,
      "invalid",
      "a..b@example.test",
      "a@localhost",
      "a@-example.test",
      "a@例.test",
      "a@b.test\n",
      "a".repeat(65) + "@example.test",
    ]) {
      expect(
        (
          await self.c.post("/api/v1/requests", {
            headers: self.headers,
            data: {
              ...command,
              newValue: value,
              idempotencyKey: crypto.randomUUID(),
            },
          })
        ).status(),
      ).toBe(400);
    }
    for (const actor of [manager, outside])
      expect(
        (
          await actor.c.post("/api/v1/requests", {
            headers: { ...actor.headers, "X-Role": "HR", "X-Tenant": "atlas" },
            data: command,
          })
        ).status(),
      ).toBe(403);
    const response = await self.c.post("/api/v1/requests", {
      headers: self.headers,
      data: command,
    });
    expect(response.status()).toBe(200);
    const r = (await checkResponse(response, "/api/v1/requests", "post")) as {
      id: string;
    };
    expect(
      (
        await (
          await self.c.post("/api/v1/requests", {
            headers: self.headers,
            data: command,
          })
        ).json()
      ).id,
    ).toBe(r.id);
    expect(
      (
        await self.c.post("/api/v1/requests", {
          headers: self.headers,
          data: { ...command, newValue: "changed@example.test" },
        })
      ).status(),
    ).toBe(409);
    for (const actor of [self, manager])
      expect(
        (
          await actor.c.post(`/api/v1/requests/${r.id}/decision`, {
            headers: actor.headers,
            data: {
              decision: "APPROVED",
              comment: "Forbidden private reviewer",
            },
          })
        ).status(),
      ).toBe(403);
    const pending = await (
      await manager.c.get("/api/v1/request-inbox?status=SUBMITTED&size=100")
    ).json();
    expect(pending.items.some((q: { id: string }) => q.id === r.id)).toBe(
      false,
    );
    const competing = await (
      await self.c.post("/api/v1/requests", {
        headers: self.headers,
        data: {
          ...command,
          newValue: "competing-personal@example.test",
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).json();
    const results = await Promise.all(
      [r.id, competing.id].map((id) =>
        hr.c.post(`/api/v1/requests/${id}/decision`, {
          headers: hr.headers,
          data: {
            decision: "APPROVED",
            comment: "Independent concurrent contact review",
          },
        }),
      ),
    );
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const loser = results[0].status() === 409 ? r.id : competing.id;
    expect(
      (
        await hr.c.post(`/api/v1/requests/${loser}/decision`, {
          headers: hr.headers,
          data: {
            decision: "REJECTED",
            comment: "Refresh after another approved update",
          },
        })
      ).status(),
    ).toBe(200);
    const current = (await (
      await self.c.get("/api/v1/employees/E003")
    ).json()) as Employee;
    expect(current.email).toBe(e.email);
    expect(current.version).toBe(e.version + 1);
    expect(
      (await (await manager.c.get("/api/v1/employees/E003")).json())
        .personalEmail,
    ).toBeNull();
    expect((await outside.c.get("/api/v1/employees/E003")).status()).toBe(403);
    const history = await (
      await manager.c.get("/api/v1/employees/E003/history")
    ).json();
    expect(
      history.some((a: { field: string }) => a.field === "personalEmail"),
    ).toBe(false);
    for (const actor of [self, manager, hr])
      expect(
        (
          await (
            await actor.c.get(
              "/api/v1/directory?search=" +
                encodeURIComponent(current.personalEmail!),
            )
          ).json()
        ).total,
      ).toBe(0);
    expect(
      (
        await (
          await self.c.post("/api/v1/requests", {
            headers: self.headers,
            data: command,
          })
        ).json()
      ).id,
    ).toBe(r.id);
    const future = new Date();
    future.setUTCDate(future.getUTCDate() + 3);
    const scheduled = await self.c.post("/api/v1/requests", {
      headers: self.headers,
      data: {
        ...command,
        newValue: "future-personal@example.test",
        effectiveDate: future.toISOString().slice(0, 10),
        version: current.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(scheduled.status()).toBe(200);
    const f = await scheduled.json();
    expect(
      (
        await hr.c.post(`/api/v1/requests/${f.id}/decision`, {
          headers: hr.headers,
          data: {
            decision: "APPROVED",
            comment: "Scheduled personal contact update",
          },
        })
      ).status(),
    ).toBe(200);
    expect(
      (await (await self.c.get("/api/v1/employees/E003")).json()).personalEmail,
    ).toBe(current.personalEmail);
  } finally {
    for (const actor of [self, hr, manager, outside]) await actor.c.dispose();
  }
});
