import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { Employee } from "@/lib/contracts";
test.describe.configure({ mode: "serial" });
async function signIn(page: Page, user: string) {
  await page.setViewportSize({ width: 1440, height: 1000 });
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
test("structured private postal address preserves errors, uses independent approval, and renders mobile history without storage markers", async ({
  page,
}) => {
  await signIn(page, "sophia");
  const before = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as Employee;
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await page
    .getByRole("button", { name: "Request structured address", exact: true })
    .click();
  await page.getByLabel("Address line 1 (required)").fill(" ");
  await page.getByLabel("City / locality (required)").fill("Montréal");
  await page.getByLabel("Country / region (required)").selectOption("CA");
  await page
    .getByLabel("Address change reason (required)")
    .fill("Employee supplied structured postal address");
  await page
    .getByRole("button", {
      name: "Submit postal address for approval",
      exact: true,
    })
    .click();
  await expect(
    page
      .getByRole("form", { name: "Postal address request" })
      .getByRole("alert"),
  ).toBeFocused();
  await expect(page.getByLabel("City / locality (required)")).toHaveValue(
    "Montréal",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  const invalidAxe = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(invalidAxe.violations).toEqual([]);
  await page.screenshot({
    path: "docs/evidence/postal-address-errors-mobile.png",
  });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.getByLabel("Address line 1 (required)").fill("18 Rue Étoile 李");
  await page.getByLabel("Address line 2 (optional)").fill("Unit 3");
  await page.getByLabel("State / region (optional)").fill("Québec");
  await page.getByLabel("Postal code (optional)").fill("H2X 1Y4");
  // Lose a successful response, then recover the same command from the preserved form.
  let committedId = "";
  await page.route("**/api/v1/employees/E003/postal-address", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    const committed = await route.fetch();
    expect(committed.status()).toBe(200);
    committedId = (await committed.json()).id;
    await route.abort("failed");
  });
  await page
    .getByRole("button", {
      name: "Submit postal address for approval",
      exact: true,
    })
    .click();
  await expect(
    page.getByRole("alert", { name: "Postal address request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Address line 1 (required)")).toHaveValue(
    "18 Rue Étoile 李",
  );
  await page.unroute("**/api/v1/employees/E003/postal-address");
  const posted = page.waitForResponse(
    (r) =>
      r.url().endsWith("/employees/E003/postal-address") &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", {
      name: "Submit postal address for approval",
      exact: true,
    })
    .click();
  const response = await posted;
  await checkResponse(
    response,
    "/api/v1/employees/{employeeId}/postal-address",
    "post",
  );
  const r = await response.json();
  expect(r.id).toBe(committedId);
  await expect(
    page.getByRole("form", { name: "Postal address request" }),
  ).toHaveCount(0);
  expect(
    (await (await page.request.get("/api/v1/employees/E003")).json()).address,
  ).toBe(before.address);
  await signOut(page);
  await signIn(page, "amelia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  const card = page
    .locator(".request-card")
    .filter({ hasText: "18 Rue Étoile 李" });
  await expect(card).not.toContainText("@POSTAL/");
  await card.getByRole("button", { name: "View request" }).click();
  await expect(page.getByRole("dialog")).toContainText("H2X 1Y4");
  await page
    .getByLabel("Decision comment")
    .fill("Independent review of supplied postal address");
  await page
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "sophia");
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await page.setViewportSize({ width: 390, height: 844 });
  const panel = page.getByRole("region", { name: "Structured postal address" });
  await expect(panel).toContainText("18 Rue Étoile 李");
  await expect(page.getByRole("dialog")).not.toContainText("@POSTAL/");
  const context = await page.request.get(
    "/api/v1/employees/E003/postal-address",
  );
  await checkResponse(
    context,
    "/api/v1/employees/{employeeId}/postal-address",
    "get",
  );
  expect((await context.json()).address.countryCode).toBe("CA");
  const issues = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(issues.violations).toEqual([]);
  await panel.scrollIntoViewIfNeeded();
  await page.screenshot({ path: "docs/evidence/postal-address-mobile.png" });
  expect(r.field).toBe("address");
});
test("postal API denies managers, other employees and tenants, validates country, and recovers duplicate typed commands", async () => {
  const self = await identity("sophia"),
    hr = await identity("amelia");
  try {
    const ctx = await (
      await self.c.get("/api/v1/employees/E003/postal-address")
    ).json();
    const data = {
      address: {
        line1: "Future synthetic street",
        line2: "",
        locality: "City",
        region: "",
        postalCode: "",
        countryCode: "US",
      },
      effectiveDate: new Date(Date.now() + 86400000 * 7)
        .toISOString()
        .slice(0, 10),
      reason: "Future postal address approval test",
      version: ctx.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    const invalid = await self.c.post("/api/v1/employees/E003/postal-address", {
      headers: self.headers,
      data: { ...data, address: { ...data.address, countryCode: "ZZ" } },
    });
    expect(invalid.status()).toBe(400);
    expect((await invalid.json()).fieldErrors.countryCode).toBeTruthy();
    const first = await self.c.post("/api/v1/employees/E003/postal-address", {
      headers: self.headers,
      data,
    });
    expect(first.status()).toBe(200);
    const r = await first.json();
    expect(
      (
        await (
          await self.c.post("/api/v1/employees/E003/postal-address", {
            headers: self.headers,
            data,
          })
        ).json()
      ).id,
    ).toBe(r.id);
    expect(
      (
        await self.c.post(`/api/v1/requests/${r.id}/decision`, {
          headers: self.headers,
          data: { decision: "APPROVED", comment: "Self review" },
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await hr.c.post(`/api/v1/requests/${r.id}/decision`, {
          headers: hr.headers,
          data: { decision: "APPROVED", comment: "Future address reviewed" },
        })
      ).status(),
    ).toBe(200);
    expect(
      (await (await self.c.get("/api/v1/employees/E003/postal-address")).json())
        .address.line1,
    ).toBe("18 Rue Étoile 李");
    expect(
      (
        await (
          await self.c.post("/api/v1/employees/E003/postal-address", {
            headers: self.headers,
            data,
          })
        ).json()
      ).id,
    ).toBe(r.id);
    expect(
      (await self.c.get("/api/v1/employees/E004/postal-address")).status(),
    ).toBe(403);
    expect(
      (
        await self.c.post("/api/v1/employees/E004/postal-address", {
          headers: self.headers,
          data,
        })
      ).status(),
    ).toBe(403);
    for (const name of ["marcus", "outsider"]) {
      const other = await identity(name);
      try {
        expect(
          (
            await other.c.get(
              "/api/v1/employees/E003/postal-address?tenant=atlas",
            )
          ).status(),
        ).toBe(403);
        expect(
          (
            await other.c.post("/api/v1/employees/E003/postal-address", {
              headers: other.headers,
              data,
            })
          ).status(),
        ).toBe(403);
      } finally {
        await other.c.dispose();
      }
    }
  } finally {
    await self.c.dispose();
    await hr.c.dispose();
  }
});

test("legacy address editor formats the structured current value and cancels without mutation", async ({
  page,
}) => {
  await signIn(page, "sophia");
  const employee = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  expect(employee.address).toContain("@POSTAL/1\n");
  await page
    .getByRole("button", { name: `View ${employee.name}`, exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("address");
  const editor = page.getByRole("dialog", {
    name: "Request an employee change",
    exact: true,
  });
  await expect(editor).toContainText("18 Rue Étoile 李");
  await expect(editor).not.toContainText("@POSTAL/");
  await expect(editor).toContainText("For separate postal fields");
  page.once("dialog", (dialog) => dialog.accept());
  await page.keyboard.press("Escape");
  await expect(editor).toHaveCount(0);
  await expect(page.getByRole("table")).toBeVisible();
  expect(
    (await (await page.request.get("/api/v1/employees/E003")).json()).address,
  ).toBe(employee.address);
});
