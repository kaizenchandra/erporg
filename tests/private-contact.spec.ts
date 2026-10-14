import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

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

for (const [field, label, prefix] of [
  ["address", "Home address", "42 Synthetic Lane"],
  ["emergencyContactName", "Emergency contact name", "Synthetic Contact"],
]) {
  test(`${label}: employee submits, manager is denied, HR approves, profile and audit persist`, async ({
    page,
  }) => {
    const proposed = `${prefix} ${Date.now()}`;
    await signIn(page, "sophia");
    await page
      .getByRole("button", { name: "View Sophia Martinez", exact: true })
      .click();
    await page
      .getByRole("dialog")
      .getByRole("button", { name: "Request a change", exact: true })
      .click();
    await page.getByLabel("Information to change").selectOption(field);
    await page.getByLabel("New value").fill(proposed);
    await page
      .getByLabel("Reason for change")
      .fill("Synthetic private contact acceptance");
    await page.getByRole("button", { name: "Submit for approval" }).click();
    await expect(
      page.getByText("Request submitted. You can track it in Approvals."),
    ).toBeVisible();
    const ownInbox = await (
      await page.request.get("/api/v1/request-inbox?status=ALL")
    ).json();
    const change = ownInbox.items.find(
      (r: { newValue: string }) => r.newValue === proposed,
    );
    expect(change).toBeDefined();
    await signOut(page);
    await signIn(page, "marcus");
    const profile = await (
      await page.request.get("/api/v1/employees/E003")
    ).json();
    for (const key of [
      "address",
      "emergencyContactName",
      "emergencyContactRelationship",
      "emergencyContactPhone",
    ])
      expect(profile[key]).toBeNull();
    const managerInbox = await (
      await page.request.get("/api/v1/request-inbox?status=ALL")
    ).json();
    expect(JSON.stringify(managerInbox)).not.toContain(proposed);
    const audit = await (
      await page.request.get("/api/v1/employees/E003/history")
    ).json();
    expect(audit.some((e: { field: string }) => e.field === field)).toBe(false);
    const token = await (await page.request.get("/api/csrf")).json();
    const denied = await page.request.post(
      `/api/v1/requests/${change.id}/decision`,
      {
        headers: { [token.headerName]: token.token },
        data: { decision: "APPROVED", comment: "Forbidden manager review" },
      },
    );
    expect(denied.status()).toBe(403);
    expect(await denied.text()).not.toContain(proposed);
    await signOut(page);
    await signIn(page, "amelia");
    await page.getByRole("button", { name: /^Approvals/ }).click();
    await page
      .locator(".request-card")
      .filter({ hasText: proposed })
      .getByRole("button", { name: "View request" })
      .click();
    await page
      .getByLabel("Decision comment")
      .fill("Private details verified by HR");
    await page.getByRole("button", { name: "Confirm approval" }).click();
    await expect(
      page.getByText("Decision recorded. The requester has been notified."),
    ).toBeVisible();
    await signOut(page);
    await signIn(page, "sophia");
    await page
      .getByRole("button", { name: "View Sophia Martinez", exact: true })
      .click();
    const detail = page
      .locator(".detail-grid > div")
      .filter({ has: page.locator("dt", { hasText: label }) });
    await expect(detail.locator("dd")).toHaveText(proposed);
    await expect(
      page
        .locator(".history-event")
        .filter({ hasText: proposed })
        .filter({ hasText: "approved" }),
    ).toBeVisible();
    const violations = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
      .analyze();
    expect(violations.violations).toEqual([]);
    await page.screenshot({
      path: `docs/evidence/private-${field}.png`,
      fullPage: true,
    });
  });
}
