import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
import type { ContactPage } from "@/features/people/contact-inbox";
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
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
test("independent HR discovers contact request in Approvals, recovers queue error and approves on mobile", async ({
  page,
}) => {
  const author = await identity("amelia");
  try {
    const before = await (
      await author.c.get("/api/v1/employees/E004/additional-contacts")
    ).json();
    const submitted = await author.c.post(
      "/api/v1/employees/E004/additional-contacts/requests",
      {
        headers: author.headers,
        data: {
          contacts: [
            {
              name: "Queue Synthetic Contact",
              relationship: "Friend",
              phone: "+1 555 010 7844",
            },
          ],
          effectiveDate: new Date().toISOString().slice(0, 10),
          reason: "Route the additional-contact decision through Approvals",
          employeeVersion: before.employeeVersion,
          idempotencyKey: crypto.randomUUID(),
        },
      },
    );
    expect(submitted.status()).toBe(200);
    const receipt = await submitted.json();
    await signIn(page, "amelia");
    await page.getByRole("button", { name: /^Approvals/ }).click();
    await page
      .getByRole("button", { name: "Emergency contacts", exact: true })
      .click();
    const queue = page.getByRole("region", {
      name: "Emergency contact approval queue",
      exact: true,
    });
    await expect(queue.getByRole("status")).toContainText("0 contact requests");
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await expect(page.getByLabel("Workspace account")).toBeVisible();
    await signIn(page, "olivia");
    await page.getByRole("button", { name: /^Approvals/ }).click();
    let fail = true;
    await page.route("**/api/v1/additional-contact-inbox?*", async (route) => {
      if (fail) {
        fail = false;
        await route.fulfill({
          status: 503,
          contentType: "application/json",
          body: JSON.stringify({ detail: "Synthetic queue interruption" }),
        });
      } else await route.continue();
    });
    await page
      .getByRole("button", { name: "Emergency contacts", exact: true })
      .click();
    await expect(queue.getByRole("alert")).toBeFocused();
    await queue
      .getByRole("button", { name: "Retry contact queue", exact: true })
      .click();
    await expect(
      queue.getByRole("button", {
        name: "Review contact request for E004",
        exact: true,
      }),
    ).toBeVisible();
    const schema = (await checkResponse(
      await page.request.get(
        "/api/v1/additional-contact-inbox?scope=REVIEW&status=SUBMITTED",
      ),
      "/api/v1/additional-contact-inbox",
    )) as ContactPage;
    expect(schema.items.some((r) => r.id === receipt.id)).toBe(true);
    await page.setViewportSize({ width: 390, height: 844 });
    await queue
      .getByRole("button", {
        name: "Review contact request for E004",
        exact: true,
      })
      .click();
    const review = page.getByRole("dialog", {
      name: "Review additional contact request",
      exact: true,
    });
    await expect(review).toContainText("Queue Synthetic Contact");
    await review
      .getByLabel("Contact decision comment (required)")
      .fill("Independent inbox review");
    await review
      .getByRole("button", {
        name: "Confirm additional contact decision",
        exact: true,
      })
      .click();
    await expect(review).not.toBeVisible();
    await expect(
      queue.getByRole("heading", {
        name: "Emergency contact requests",
        exact: true,
      }),
    ).toBeFocused();
    await expect(queue.getByRole("status")).toContainText("0 contact requests");
    await queue.getByLabel("Contact queue status").selectOption("APPROVED");
    const card = queue.getByRole("article", {
      name: `Contact queue request ${receipt.id}`,
      exact: true,
    });
    await card
      .getByText("Inspect contact snapshots and history", { exact: true })
      .click();
    await expect(card).toContainText("Independent inbox review");
    await expect(card).toContainText("Queue Synthetic Contact");
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
    await queue
      .getByRole("heading", { name: "Emergency contact requests", exact: true })
      .scrollIntoViewIfNeeded();
    await page.screenshot({ path: "docs/evidence/contact-inbox-mobile.png" });
    const current = await (
      await author.c.get("/api/v1/employees/E004/additional-contacts")
    ).json();
    expect(current.contacts[0].name).toBe("Queue Synthetic Contact");
    expect(current.employeeVersion).toBe(before.employeeVersion + 1);
  } finally {
    await author.c.dispose();
  }
});
test("contact queue API enforces server-owned scope, independent authors and bounded contracts", async () => {
  const self = await identity("sophia"),
    manager = await identity("marcus"),
    other = await identity("outsider"),
    hr = await identity("amelia"),
    reviewer = await identity("olivia");
  try {
    for (const user of [self, manager, other])
      expect(
        (
          await user.c.get("/api/v1/additional-contact-inbox?scope=REVIEW", {
            headers: { "X-Role": "HR", "X-Tenant-ID": "atlas" },
          })
        ).status(),
      ).toBe(403);
    const own = (await checkResponse(
      await self.c.get(
        "/api/v1/additional-contact-inbox?scope=OWN&status=ALL&employeeId=E004&tenant=other",
      ),
      "/api/v1/additional-contact-inbox",
    )) as ContactPage;
    expect(own.items.every((r) => r.employeeId === "E003")).toBe(true);
    const outside = (await checkResponse(
      await other.c.get(
        "/api/v1/additional-contact-inbox?scope=OWN&status=ALL&employeeId=E003",
      ),
      "/api/v1/additional-contact-inbox",
    )) as ContactPage;
    expect(outside.total).toBe(0);
    const authorQueue = (await (
      await hr.c.get("/api/v1/additional-contact-inbox?scope=REVIEW&status=ALL")
    ).json()) as ContactPage;
    expect(authorQueue.items.some((r) => r.employeeId === "E004")).toBe(false);
    const reviewed = (await checkResponse(
      await reviewer.c.get(
        "/api/v1/additional-contact-inbox?scope=REVIEW&status=APPROVED&size=1&page=0",
      ),
      "/api/v1/additional-contact-inbox",
    )) as ContactPage;
    expect(reviewed.items.length).toBe(1);
    expect(reviewed.total).toBeGreaterThanOrEqual(1);
    for (const query of [
      "scope=ALL",
      "scope=OWN&size=51",
      "scope=OWN&page=-1",
      "scope=OWN&status=WITHDRAWN",
    ]) {
      expect(
        (
          await self.c.get("/api/v1/additional-contact-inbox?" + query)
        ).status(),
      ).toBe(400);
    }
  } finally {
    await Promise.all(
      [self, manager, other, hr, reviewer].map((u) => u.c.dispose()),
    );
  }
});
