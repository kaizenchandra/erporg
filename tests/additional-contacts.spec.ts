import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type {
  ContactContext,
  ContactRequest,
} from "@/features/people/additional-contacts";
const path = "/api/v1/employees/E003/additional-contacts";
const schemaPath = "/api/v1/employees/{employeeId}/additional-contacts";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
async function signOut(page: Page) {
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
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
test("private additional contacts recover an uncertain submission, receive independent HR review and render on mobile", async ({
  page,
}) => {
  await signIn(page, "sophia");
  const before = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  const section = page.getByRole("region", {
    name: "Additional emergency contacts",
    exact: true,
  });
  await expect(
    section.getByText("No additional contacts recorded.", { exact: true }),
  ).toBeVisible();
  await section
    .getByRole("button", {
      name: "Request additional contact changes",
      exact: true,
    })
    .click();
  const editor = page.getByRole("dialog", {
    name: "Request additional contact changes",
    exact: true,
  });
  await editor
    .getByRole("button", { name: "Add additional contact", exact: true })
    .click();
  await editor
    .getByLabel("Contact name 1 (required)", { exact: true })
    .fill("Zoë 李 Synthetic Contact");
  await editor
    .getByLabel("Relationship 1 (required)", { exact: true })
    .fill("Sibling");
  await editor
    .getByLabel("Phone number 1 (required)", { exact: true })
    .fill("letters");
  await editor
    .getByLabel("Contact change reason (required)")
    .fill("Record two trusted synthetic emergency contacts");
  await editor
    .getByRole("button", {
      name: "Submit additional contacts for approval",
      exact: true,
    })
    .click();
  await expect(editor.getByRole("alert")).toBeFocused();
  await expect(
    editor.getByLabel("Phone number 1 (required)", { exact: true }),
  ).toHaveAttribute("aria-invalid", "true");
  await expect(
    editor.getByLabel("Contact name 1 (required)", { exact: true }),
  ).toHaveValue("Zoë 李 Synthetic Contact");
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(editor).toBeVisible();
  await editor
    .getByLabel("Phone number 1 (required)", { exact: true })
    .fill("+1 555 010 7311");
  await editor
    .getByRole("button", { name: "Add additional contact", exact: true })
    .click();
  await editor
    .getByLabel("Contact name 2 (required)", { exact: true })
    .fill("Sam Synthetic Contact");
  await editor
    .getByLabel("Relationship 2 (required)", { exact: true })
    .fill("Friend");
  await editor
    .getByLabel("Phone number 2 (required)", { exact: true })
    .fill("+1 555 010 7322");
  let intercepted = false;
  await page.route(
    "**/api/v1/employees/E003/additional-contacts/requests",
    async (route) => {
      if (route.request().method() === "POST" && !intercepted) {
        intercepted = true;
        const real = await route.fetch();
        expect(real.status()).toBe(200);
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ detail: "Synthetic interrupted response" }),
        });
      } else await route.continue();
    },
  );
  await editor
    .getByRole("button", {
      name: "Submit additional contacts for approval",
      exact: true,
    })
    .click();
  await expect(editor.getByRole("alert")).toContainText(
    "Retry unchanged values",
  );
  await editor
    .getByRole("button", {
      name: "Submit additional contacts for approval",
      exact: true,
    })
    .click();
  await expect(editor).not.toBeVisible();
  await expect(
    section.getByText("Pending review", { exact: true }),
  ).toBeVisible();
  const pending = (await checkResponse(
    await page.request.get(path),
    schemaPath,
  )) as ContactContext;
  expect(pending.contacts).toEqual([]);
  expect(pending.requests.total).toBe(1);
  await signOut(page);
  await signIn(page, "amelia");
  await page.getByLabel("Search employees").fill("E003");
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  const calendarPanel = page.getByRole("region", {
    name: "Employee company calendar",
  });
  await calendarPanel
    .getByRole("button", { name: "Preview employee date", exact: true })
    .click();
  await expect(calendarPanel.getByRole("alert")).toBeFocused();
  const changes = page.getByRole("region", {
    name: "Employee change history",
    exact: true,
  });
  await changes
    .getByRole("combobox", { name: "History action", exact: true })
    .selectOption("REJECTED");
  await expect(changes.getByRole("status")).toContainText("Page 1");
  await page
    .getByRole("button", {
      name: "Show calendar assignment history",
      exact: true,
    })
    .click();
  await expect(
    page.getByRole("region", {
      name: "Employee calendar history",
      exact: true,
    }),
  ).toContainText("0 approved calendar records");
  await page
    .getByRole("button", {
      name: "Review additional contact request",
      exact: true,
    })
    .click();
  const review = page.getByRole("dialog", {
    name: "Review additional contact request",
    exact: true,
  });
  await expect(review).toContainText("Zoë 李 Synthetic Contact");
  await review
    .getByLabel("Contact decision comment (required)")
    .fill("Independent review of supplied contacts");
  const response = page.waitForResponse(
    (r) => r.request().method() === "POST" && r.url().endsWith("/decision"),
  );
  await review
    .getByRole("button", {
      name: "Confirm additional contact decision",
      exact: true,
    })
    .click();
  await checkResponse(
    await response,
    schemaPath + "/requests/{id}/decision",
    "post",
  );
  await expect(review).not.toBeVisible();
  await expect(calendarPanel).toContainText(
    "Employee changed. Preview the date again",
  );
  await expect(calendarPanel.getByRole("alert")).toHaveCount(0);
  await expect(
    changes.getByRole("combobox", { name: "History action", exact: true }),
  ).toHaveValue("ALL");
  await expect(
    page.getByRole("button", {
      name: "Show calendar assignment history",
      exact: true,
    }),
  ).toHaveAttribute("aria-expanded", "false");
  await expect(
    page
      .getByRole("region", { name: "Employee calendar history", exact: true })
      .getByRole("status"),
  ).toHaveCount(0);
  await calendarPanel
    .getByRole("button", { name: "Preview employee date", exact: true })
    .click();
  await expect(calendarPanel.getByRole("alert")).toBeFocused();
  await signOut(page);
  await signIn(page, "sophia");
  await page.setViewportSize({ width: 390, height: 844 });
  await page
    .getByRole("button", { name: "Open your profile", exact: true })
    .click();
  await expect(
    section.getByText("Zoë 李 Synthetic Contact", { exact: true }).first(),
  ).toBeVisible();
  await expect(
    section.getByText("Sam Synthetic Contact", { exact: true }).first(),
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
  await page.screenshot({
    path: "docs/evidence/additional-contacts-mobile.png",
  });
  const reopen = section.getByRole("button", {
    name: "Request additional contact changes",
    exact: true,
  });
  await reopen.click();
  await expect(editor).toBeVisible();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  await page.keyboard.press("Escape");
  await expect(editor).not.toBeVisible();
  await expect(
    page.getByRole("dialog", { name: "Employee profile", exact: true }),
  ).toBeVisible();
  await expect(reopen).toBeFocused();
  const after = await (await page.request.get("/api/v1/employees/E003")).json();
  expect(after.emergencyContactName).toBe(before.emergencyContactName);
  expect(after.legalName).toBe(before.legalName);
  await signOut(page);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await signIn(page, "marcus");
  await page.getByLabel("Search employees").fill("E003");
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await expect(section).toHaveCount(0);
  expect((await page.request.get(path)).status()).toBe(403);
});
test("contact APIs enforce scopes, bounds, stable replay and one concurrent future approval", async () => {
  const self = await identity("sophia"),
    hr = await identity("amelia"),
    manager = await identity("marcus"),
    outsider = await identity("outsider");
  try {
    for (const person of [manager, outsider]) {
      expect(
        (
          await person.c.get(path, {
            headers: {
              "X-Tenant-ID": "atlas",
              "X-Role": "HR",
              "X-Employee-ID": "E003",
            },
          })
        ).status(),
      ).toBe(403);
      expect(
        (
          await person.c.post(path + "/requests", {
            headers: person.headers,
            data: {},
          })
        ).status(),
      ).toBe(403);
    }
    expect(
      (await self.c.get("/api/v1/employees/E004/additional-contacts")).status(),
    ).toBe(403);
    const ctx = (await checkResponse(
      await self.c.get(path),
      schemaPath,
    )) as ContactContext;
    const future = new Date();
    future.setUTCDate(future.getUTCDate() + 5);
    const body = {
      contacts: [],
      effectiveDate: future.toISOString().slice(0, 10),
      reason: "Remove additional contacts on a future business date",
      employeeVersion: ctx.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await self.c.post(path + "/requests", {
          headers: self.headers,
          data: {
            ...body,
            contacts: Array(5).fill({
              name: "Synthetic",
              relationship: "Friend",
              phone: "+1 555 010 7399",
            }),
          },
        })
      ).status(),
    ).toBe(400);
    const submitted = (await checkResponse(
      await self.c.post(path + "/requests", {
        headers: self.headers,
        data: body,
      }),
      schemaPath + "/requests",
      "post",
    )) as ContactRequest;
    const replay = (await checkResponse(
      await self.c.post(path + "/requests", {
        headers: self.headers,
        data: body,
      }),
      schemaPath + "/requests",
      "post",
    )) as ContactRequest;
    expect(replay.id).toBe(submitted.id);
    expect(
      (
        await self.c.post(path + "/requests", {
          headers: self.headers,
          data: { ...body, reason: "Conflicting same key" },
        })
      ).status(),
    ).toBe(409);
    const decisionPath = path + `/requests/${submitted.id}/decision`;
    const decision = {
      decision: "APPROVED",
      comment: "Independent future removal review",
      version: submitted.version,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await self.c.post(decisionPath, {
          headers: self.headers,
          data: decision,
        })
      ).status(),
    ).toBe(403);
    const results = await Promise.all([
      hr.c.post(decisionPath, { headers: hr.headers, data: decision }),
      hr.c.post(decisionPath, {
        headers: hr.headers,
        data: { ...decision, idempotencyKey: crypto.randomUUID() },
      }),
    ]);
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    const after = (await checkResponse(
      await self.c.get(path),
      schemaPath,
    )) as ContactContext;
    expect(after.contacts).toEqual(ctx.contacts);
    expect(after.employeeVersion).toBe(ctx.employeeVersion + 1);
    expect(after.requests.total).toBe(ctx.requests.total + 1);
    expect(
      after.requests.items.find((r) => r.id === submitted.id),
    ).toMatchObject({
      status: "APPROVED",
      oldContacts: ctx.contacts,
      contacts: [],
      version: 1,
    });
    const approved = (await results
      .find((r) => r.status() === 200)!
      .json()) as ContactRequest;
    expect(approved.decidedBy).toBe("amelia");
    expect((await self.c.get(path + "?size=51")).status()).toBe(400);
    const filtered = (await (
      await hr.c.get(path + "?status=SUBMITTED&page=0&size=1")
    ).json()) as ContactContext;
    expect(filtered.requests.total).toBe(0);
  } finally {
    await Promise.all([
      self.c.dispose(),
      hr.c.dispose(),
      manager.c.dispose(),
      outsider.c.dispose(),
    ]);
  }
});
