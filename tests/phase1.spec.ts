import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
async function login(page: Page, user = "amelia") {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
}
test("directory, search, filters, profile and accessible desktop shell", async ({
  page,
}) => {
  await login(page);
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(8);
  const total = (
    await (await page.request.get("/api/v1/people-summary")).json()
  ).total;
  await page.getByRole("button", { name: "Next page", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(Math.min(8, total - 8));
  await page
    .getByRole("button", { name: "Previous page", exact: true })
    .click();
  await expect(page.locator("tbody tr")).toHaveCount(8);
  await page.getByRole("textbox", { name: "Search employees" }).fill("Sophia");
  await expect(
    page.getByRole("button", {
      name: "Sophia Martinez Senior Product Designer",
    }),
  ).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByText("sophia.martinez@example.test")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await page
    .getByRole("textbox", { name: "Search employees" })
    .fill("no matching person");
  await expect(page.getByText("No employees found")).toBeVisible();
  await page.getByRole("textbox", { name: "Search employees" }).fill("");
  await page
    .getByLabel("Department", { exact: true })
    .selectOption("Engineering");
  await expect(page.locator("tbody tr")).toHaveCount(4);
  await page
    .getByLabel("Department", { exact: true })
    .selectOption("All departments");
  await page.screenshot({
    path: "docs/evidence/directory-desktop.png",
    fullPage: true,
  });
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(result.violations).toEqual([]);
});
test("employee submits contact change; independent HR approves; profile and audit persist", async ({
  page,
}) => {
  await login(page, "sophia");
  await page
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  const phone = "+44 7700 " + Date.now().toString().slice(-6);
  await page.getByLabel("New value").fill(phone);
  await page
    .getByLabel("Reason for change")
    .fill("Browser acceptance: update synthetic contact number");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await expect(page.getByText(phone, { exact: true }).first()).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("button", { name: "Enter workspace" }),
  ).toBeVisible();
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
  await page.getByRole("button", { name: /^Approvals/ }).click();
  const card = page.locator(".request-card").filter({ hasText: phone });
  await card.getByRole("button", { name: "View request" }).click();
  await page
    .getByLabel("Decision comment")
    .fill("Verified by independent HR reviewer");
  await page.getByRole("button", { name: "Confirm approval" }).click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await page.getByRole("button", { name: "People", exact: true }).click();
  await page.getByRole("textbox", { name: "Search employees" }).fill("Sophia");
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await expect(page.locator("dd").filter({ hasText: phone })).toBeVisible();
  await expect(
    page.getByText("Verified by independent HR reviewer").first(),
  ).toBeVisible();
  await page.screenshot({
    path: "docs/evidence/profile-desktop.png",
    fullPage: true,
  });
});
test("responsive employee self-service and keyboard focus", async ({
  page,
}) => {
  await login(page, "sophia");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".mobile-employee")).toHaveCount(1);
  await expect(page.getByRole("table")).not.toBeVisible();
  await page
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Information to change")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({ path: "docs/evidence/mobile.png", fullPage: true });
});

test("approval inbox and profile/form accessibility, rejection, preserved input and expired-session clearing", async ({
  page,
}) => {
  const requester = await request.newContext({
    baseURL: "http://127.0.0.1:3000",
  });
  let csrf = await (await requester.get("/api/csrf")).json();
  await requester.post("/api/login", {
    form: { username: "sophia", password: "atlas-demo" },
    headers: { [csrf.headerName]: csrf.token },
  });
  csrf = await (await requester.get("/api/csrf")).json();
  const employee = (await (await requester.get("/api/v1/employees")).json())[0];
  const proposed = "+44 7700 " + Date.now().toString().slice(-6);
  const submission = await requester.post("/api/v1/requests", {
    headers: { [csrf.headerName]: csrf.token },
    data: {
      employeeId: employee.id,
      field: "phone",
      newValue: proposed,
      effectiveDate: new Date().toISOString().slice(0, 10),
      reason: "Request for rejection acceptance",
      version: employee.version,
      idempotencyKey: crypto.randomUUID(),
    },
  });
  expect(submission.status()).toBe(200);
  await requester.dispose();
  await login(page);
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page.screenshot({
    path: "docs/evidence/approval-inbox.png",
    fullPage: true,
  });
  let result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(
    result.violations.map((v) => ({
      id: v.id,
      nodes: v.nodes.map((n) => n.target),
    })),
  ).toEqual([]);
  const first = page
    .locator(".request-card")
    .filter({ hasText: proposed })
    .first();
  await first.getByRole("button", { name: "View request" }).click();
  await page.getByLabel("Decision", { exact: true }).selectOption("REJECTED");
  await page
    .getByLabel("Decision comment")
    .fill("Additional verification needed");
  await page.getByRole("button", { name: "Confirm rejection" }).click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  await page.getByRole("button", { name: "People", exact: true }).click();
  await page
    .getByRole("button", { name: "View Amelia Bennett", exact: true })
    .click();
  result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(
    result.violations.map((v) => ({
      id: v.id,
      nodes: v.nodes.map((n) => n.target),
    })),
  ).toEqual([]);
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change" })
    .click();
  await page.getByLabel("New value").fill("invalid contact");
  await page.getByLabel("Reason for change").fill("Check validation");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "valid phone number" }),
  ).toBeVisible();
  await expect(
    page.getByRole("alert", { name: "Change request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("New value")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
  await page.getByRole("link", { name: /Enter a valid phone number/ }).click();
  await expect(page.getByLabel("New value")).toBeFocused();
  await expect(page.getByLabel("New value")).toHaveValue("invalid contact");
  result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(
    result.violations.map((v) => ({
      id: v.id,
      nodes: v.nodes.map((n) => n.target),
    })),
  ).toEqual([]);
  await page.context().clearCookies();
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByRole("button", { name: "Enter workspace" }),
  ).toBeVisible();
  await expect(page.getByRole("dialog")).not.toBeVisible();
});

test("approval inbox navigates server pages and resets when filtering", async ({
  page,
}) => {
  await login(page);
  const token = await (await page.request.get("/api/csrf")).json();
  const employee = await (
    await page.request.get("/api/v1/employees/E001")
  ).json();
  for (let i = 0; i < 13; i++) {
    const response = await page.request.post("/api/v1/requests", {
      headers: { [token.headerName]: token.token },
      data: {
        employeeId: employee.id,
        field: "phone",
        newValue: `+1 202 555 ${String(7000 + i)}`,
        effectiveDate: new Date().toISOString().slice(0, 10),
        reason: "Synthetic inbox pagination",
        version: employee.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(response.status()).toBe(200);
  }
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await expect(page.locator(".request-card")).toHaveCount(12);
  const first = await page.locator(".request-card").first().innerText();
  await page
    .getByRole("button", { name: "Next requests", exact: true })
    .click();
  await expect(page.getByText(/matching requests · Page 2/)).toBeVisible();
  await expect(page.locator(".request-card").first()).not.toHaveText(first);
  await page.getByRole("button", { name: "All requests", exact: true }).click();
  await expect(page.getByText(/matching requests · Page 1/)).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Previous requests", exact: true }),
  ).toBeDisabled();
});
