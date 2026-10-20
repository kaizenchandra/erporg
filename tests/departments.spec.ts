import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(page.getByRole("table")).toBeVisible();
}
async function signOut(page: Page) {
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Enter workspace" }),
  ).toBeVisible();
}
test("HR configures a department; manager transfers an employee; HR approves the real reference", async ({
  page,
}) => {
  const suffix = Date.now().toString();
  const code = `TEST-${suffix}`,
    name = `Platform ${suffix}`,
    costCentre = `CC-${suffix}`;
  await signIn(page, "amelia");
  await page
    .getByRole("button", { name: "Organisation view", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Create department", exact: true })
    .click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Department code").fill(code);
  await dialog.getByLabel("Department name").fill(name);
  await dialog.getByLabel("Cost-centre code").fill(costCentre);
  await dialog
    .getByLabel("Configuration reason")
    .fill("Synthetic department configuration acceptance");
  await dialog
    .getByRole("button", { name: "Create department", exact: true })
    .click();
  await expect(
    page.getByText("Department created and audit recorded."),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
  await checkResponse(
    await page.request.get("/api/v1/organisation/departments"),
    "/api/v1/organisation/departments",
  );
  await checkResponse(
    await page.request.get("/api/v1/organisation/history"),
    "/api/v1/organisation/history",
  );
  await page
    .getByText("Department configuration history", { exact: true })
    .click();
  await expect(
    page.getByText(`${code} · Created`, { exact: true }),
  ).toBeVisible();
  const axe = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(axe.violations).toEqual([]);
  await page.screenshot({
    path: "docs/evidence/department-reference.png",
    fullPage: true,
  });
  await signOut(page);
  await signIn(page, "marcus");
  const token = await (await page.request.get("/api/csrf")).json();
  const denied = await page.request.post("/api/v1/organisation/departments", {
    headers: { [token.headerName]: token.token },
    data: {
      code: "FORGED",
      name: "Forbidden",
      parentCode: null,
      costCentre: "CC-FORGED",
      effectiveFrom: new Date().toISOString().slice(0, 10),
      reason: "No HR permission",
      idempotencyKey: crypto.randomUUID(),
    },
  });
  expect(denied.status()).toBe(403);
  await page
    .getByRole("button", { name: "View Sophia Martinez", exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page.getByLabel("Information to change").selectOption("department");
  await page
    .getByRole("dialog")
    .getByLabel("Find a department", { exact: true })
    .fill(name);
  await page.getByLabel("New value").selectOption(name);
  await page
    .getByLabel("Reason for change")
    .fill("Synthetic department transfer");
  await page.getByRole("button", { name: "Submit for approval" }).click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  await signOut(page);
  await signIn(page, "amelia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: name })
    .getByRole("button", { name: "View request" })
    .click();
  await page
    .getByLabel("Decision comment")
    .fill("Reference and effective date verified");
  await page.getByRole("button", { name: "Confirm approval" }).click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  const employee = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  expect(employee.department).toBe(name);
});
