import {
  test,
  expect,
  request,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
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
async function identity(user: string) {
  const client = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  const token = await (await client.get("/api/csrf")).json();
  expect(
    (
      await client.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [token.headerName]: token.token },
      })
    ).status(),
  ).toBe(204);
  const fresh = await (await client.get("/api/csrf")).json();
  return { client, headers: { [fresh.headerName]: fresh.token } };
}
test("hire submission, independent HR approval and assigned employee profile work through UI", async ({
  page,
}) => {
  const suffix = Date.now(),
    name = `Zoe Synthetic ${suffix}`,
    email = `zoe-${suffix}@example.test`;
  await signIn(page, "amelia");
  await page.getByRole("button", { name: "Hiring", exact: true }).click();
  await page
    .getByRole("button", { name: "Submit a hire", exact: true })
    .click();
  await page
    .getByRole("button", { name: "Submit for HR review", exact: true })
    .click();
  await expect(
    page.getByRole("alert", { name: "Hire request errors" }),
  ).toBeFocused();
  await expect(page.getByLabel("Employee name")).toHaveAttribute(
    "aria-invalid",
    "true",
  );
  await page.getByLabel("Employee name").fill(name);
  await page.getByLabel("Work email").fill(email);
  await page.getByLabel("Contact number").fill("+1 202 555 8888");
  await page.getByLabel("Job title").fill("Synthetic Analyst");
  await page.getByLabel("Work location").fill("London, UK");
  await page.getByLabel("Reporting manager ID").fill("E002");
  await page
    .getByRole("dialog")
    .getByLabel("Find a department", { exact: true })
    .fill("Design");
  await page
    .getByRole("combobox", { name: "Department", exact: false })
    .selectOption("Design");
  await page.getByLabel("Hiring reason").fill("Synthetic hiring acceptance");
  const axe = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(axe.violations).toEqual([]);
  await page.getByRole("button", { name: "Submit for HR review" }).click();
  await expect(
    page.getByText("Hire submitted for independent HR review."),
  ).toBeVisible();
  await page
    .locator(".request-card")
    .filter({ hasText: name })
    .getByRole("button", { name: "Review hire" })
    .click();
  await expect(
    page.getByText("Another HR employee must review your hiring request."),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await signOut(page);
  await signIn(page, "olivia");
  await page.getByRole("button", { name: "Hiring", exact: true }).click();
  await checkResponse(
    await page.request.get("/api/v1/hiring/requests"),
    "/api/v1/hiring/requests",
  );
  await page
    .locator(".request-card")
    .filter({ hasText: name })
    .getByRole("button", { name: "Review hire" })
    .click();
  await page
    .getByLabel("Hiring decision comment")
    .fill("Verified independently by HR");
  await page.getByRole("button", { name: "Confirm hire approval" }).click();
  await expect(page.getByText("Hiring decision recorded.")).toBeVisible();
  await page.getByLabel("Hiring status").selectOption("APPROVED");
  const card = page.locator(".request-card").filter({ hasText: name });
  await expect(card).toContainText("Payroll setup pending");
  await card.getByRole("button", { name: "Open employee" }).click();
  await expect(
    page.getByRole("dialog").getByRole("heading", { name, exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("dialog").getByText("Onboarding", { exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "docs/evidence/approved-hire-profile.png",
    fullPage: true,
  });
});
test("hire API denies non-HR/self review, replays stable and concurrent approvals create one ID", async () => {
  const contexts: APIRequestContext[] = [];
  try {
    const author = await identity("amelia"),
      reviewer = await identity("olivia"),
      employee = await identity("sophia");
    contexts.push(author.client, reviewer.client, employee.client);
    expect(
      (await employee.client.get("/api/v1/hiring/requests")).status(),
    ).toBe(403);
    const payload = {
      name: "Zoe Synthetic API",
      email: `zoe-api-${Date.now()}@example.test`,
      phone: "+1 202 555 9999",
      title: "Synthetic Analyst",
      department: "Design",
      location: "London, UK",
      managerId: "E002",
      startDate: new Date().toISOString().slice(0, 10),
      reason: "Concurrent hiring acceptance",
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await employee.client.post("/api/v1/hiring/requests", {
          headers: employee.headers,
          data: payload,
        })
      ).status(),
    ).toBe(403);
    const first = await author.client.post("/api/v1/hiring/requests", {
      headers: author.headers,
      data: payload,
    });
    expect(first.status()).toBe(200);
    const hire = (await checkResponse(
      first,
      "/api/v1/hiring/requests",
      "post",
    )) as { id: string };
    expect(
      (
        await employee.client.get(`/api/v1/hiring/requests/${hire.id}/history`)
      ).status(),
    ).toBe(403);
    const replay = await author.client.post("/api/v1/hiring/requests", {
      headers: author.headers,
      data: payload,
    });
    expect((await replay.json()).id).toBe(hire.id);
    expect(
      (
        await author.client.post("/api/v1/hiring/requests", {
          headers: author.headers,
          data: { ...payload, reason: "Changed" },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await author.client.post(
          `/api/v1/hiring/requests/${hire.id}/decision`,
          {
            headers: author.headers,
            data: { decision: "APPROVED", comment: "Self review" },
          },
        )
      ).status(),
    ).toBe(403);
    const decisions = await Promise.all(
      [1, 2].map(() =>
        reviewer.client.post(`/api/v1/hiring/requests/${hire.id}/decision`, {
          headers: reviewer.headers,
          data: { decision: "APPROVED", comment: "Independent review" },
        }),
      ),
    );
    expect(decisions.map((r) => r.status()).sort()).toEqual([200, 409]);
    const approved = (await checkResponse(
      decisions.find((r) => r.status() === 200)!,
      "/api/v1/hiring/requests/{id}/decision",
      "post",
    )) as { employeeId: string };
    expect(approved.employeeId).toMatch(/^E-[a-f0-9-]{36}$/);
    const directory = await (
      await author.client.get(
        `/api/v1/directory?search=${encodeURIComponent(payload.email)}`,
      )
    ).json();
    expect(directory.total).toBe(1);
    expect(directory.items[0].id).toBe(approved.employeeId);
    await checkResponse(
      await author.client.get(`/api/v1/hiring/requests/${hire.id}/history`),
      "/api/v1/hiring/requests/{id}/history",
    );
  } finally {
    await Promise.all(contexts.map((c) => c.dispose()));
  }
});
