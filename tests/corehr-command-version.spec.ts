import { test, expect, type Page } from "@playwright/test";

async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Sign out", exact: true }),
  ).toBeVisible();
}

test("fractional employee revision is rejected without a request; preserved form recovers through independent approval", async ({
  page,
}) => {
  const proposed = `BK Contact ${crypto.randomUUID().slice(0, 8)}`;
  await signIn(page, "sophia");
  const before = (await (
    await page.request.get("/api/v1/employees/E003")
  ).json()) as { name: string; version: number; emergencyContactName: string };
  await page
    .getByRole("button", { name: `View ${before.name}`, exact: true })
    .click();
  await page
    .getByRole("dialog")
    .getByRole("button", { name: "Request a change", exact: true })
    .click();
  await page
    .getByLabel("Information to change")
    .selectOption("emergencyContactName");
  const value = page.getByRole("textbox", { name: /^New value/ });
  const reason = page.getByRole("textbox", { name: /^Reason for change/ });
  await value.fill(proposed);
  await reason.fill("Synthetic BK preserved private change");
  // Change only the outgoing version; the response and persistence are real.
  await page.route(
    "**/api/v1/requests",
    async (route) => {
      const body = route.request().postDataJSON() as { version: number };
      await route.continue({
        postData: JSON.stringify({ ...body, version: body.version + 0.5 }),
      });
    },
    { times: 1 },
  );
  const rejected = page.waitForResponse(
    (r) =>
      r.url().endsWith("/api/v1/requests") && r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  expect((await rejected).status()).toBe(400);
  const error = page.getByRole("alert", {
    name: "Change request errors",
    exact: true,
  });
  await expect(error).toBeFocused();
  await expect(error).toContainText("exact nonnegative employee version");
  await expect(value).toHaveValue(proposed);
  await expect(reason).toHaveValue("Synthetic BK preserved private change");
  const unchanged = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  expect(unchanged.version).toBe(before.version);
  expect(unchanged.emergencyContactName).toBe(before.emergencyContactName);
  const inboxBefore = (await (
    await page.request.get("/api/v1/request-inbox?status=ALL&size=50")
  ).json()) as { items: { newValue: string }[] };
  expect(inboxBefore.items.some((r) => r.newValue === proposed)).toBe(false);
  await page
    .getByRole("button", { name: "Submit for approval", exact: true })
    .click();
  await expect(
    page.getByText("Request submitted. You can track it in Approvals."),
  ).toBeVisible();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
  await signIn(page, "amelia");
  await page.getByRole("button", { name: /^Approvals/ }).click();
  await page
    .locator(".request-card")
    .filter({ hasText: proposed })
    .getByRole("button", { name: "View request", exact: true })
    .click();
  await page
    .getByLabel("Decision comment")
    .fill("Independent BK exact revision review");
  await page
    .getByRole("button", { name: "Confirm approval", exact: true })
    .click();
  await expect(
    page.getByText("Decision recorded. The requester has been notified."),
  ).toBeVisible();
  const current = await (
    await page.request.get("/api/v1/employees/E003")
  ).json();
  expect(current.emergencyContactName).toBe(proposed);
  expect(current.version).toBe(before.version + 1);
  const history = (await (
    await page.request.get("/api/v1/employees/E003/history")
  ).json()) as { newValue: string; action: string }[];
  expect(
    history.filter((r) => r.newValue === proposed && r.action === "SUBMITTED"),
  ).toHaveLength(1);
  expect(
    history.filter((r) => r.newValue === proposed && r.action === "APPROVED"),
  ).toHaveLength(1);
});
