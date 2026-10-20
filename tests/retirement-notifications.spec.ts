import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
const prompt =
  "A declared-reference retirement needs independent HR review. Open Approvals and choose Reference retirements.";
const approved =
  "Your declared-reference retirement was approved. Open Settings for its effective date and history.";
async function identity(user: string) {
  const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  const csrf = await (await c.get("/api/csrf")).json();
  expect(
    (
      await c.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [csrf.headerName]: csrf.token },
      })
    ).status(),
  ).toBe(204);
  const token = await (await c.get("/api/csrf")).json();
  return { c, headers: { [token.headerName]: token.token } };
}
async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByRole("table")).toBeVisible();
}
test("retirement prompt and decision reach the correct live inbox without replay duplicates or confidential detail", async ({
  page,
}) => {
  const author = await identity("amelia"),
    reviewer = await identity("olivia");
  const others = await Promise.all([identity("sophia"), identity("outsider")]);
  const count = async (client: typeof author.c, message: string) => {
    const response = await client.get("/api/v1/notifications");
    await checkResponse(response, "/api/v1/notifications");
    return (await response.json()).filter(
      (n: { message: string }) => n.message === message,
    ).length;
  };
  try {
    const before = await count(reviewer.c, prompt);
    const original = await count(author.c, approved);
    const excluded = await Promise.all(others.map((o) => count(o.c, prompt)));
    await signIn(page, "olivia");
    const code = "NOTICE-" + crypto.randomUUID().slice(0, 8).toUpperCase();
    const date = new Date().toISOString().slice(0, 10);
    const create = await author.c.post("/api/v1/legal-entities", {
      headers: author.headers,
      data: {
        entity: {
          code,
          name: "Private synthetic reference",
          countryCode: "GB",
          currencyCode: "GBP",
          timeZone: "Europe/London",
          effectiveFrom: date,
        },
        reason: "Private creation reason",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(create.status()).toBe(200);
    const base = `/api/v1/legal-entities/${code}/retirement`;
    const command = {
      effectiveDate: date,
      reason: "Private retirement reason",
      version: 0,
      idempotencyKey: crypto.randomUUID(),
    };
    const submit = await author.c.post(base + "/requests", {
      headers: author.headers,
      data: command,
    });
    expect(submit.status()).toBe(200);
    const proposal = await submit.json();
    expect(
      (
        await author.c.post(base + "/requests", {
          headers: author.headers,
          data: command,
        })
      ).status(),
    ).toBe(200);
    expect(await count(reviewer.c, prompt)).toBe(before + 1);
    await page.getByRole("button", { name: /^Notifications \(/ }).click();
    const dialog = page.getByRole("dialog", {
      name: "Notifications",
      exact: true,
    });
    await expect(dialog.getByText(prompt, { exact: true })).toHaveCount(
      before + 1,
    );
    await expect(dialog).not.toContainText(code);
    await expect(dialog).not.toContainText("Private retirement reason");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Settings", exact: true }).click();
    await page.getByLabel("Entity code or name").fill(code);
    await page
      .getByRole("button", { name: "Search references", exact: true })
      .click();
    await page
      .getByRole("button", { name: `Manage retirement · ${code}`, exact: true })
      .click();
    await page
      .getByLabel("Retirement review comment (required)")
      .fill("Private independent review comment");
    const deciding = page.waitForRequest(
      (r) =>
        r.url().endsWith(`/requests/${proposal.id}/decision`) &&
        r.method() === "POST",
    );
    await page
      .getByRole("button", { name: "Confirm retirement decision", exact: true })
      .click();
    const submittedDecision = (await deciding).postDataJSON();
    await expect(
      page
        .getByRole("region", { name: "Reference retirement context" })
        .getByText(/^Retired ·/),
    ).toBeVisible();
    const state = await (await reviewer.c.get(base)).json();
    const decision = state.requests[0].decision;
    expect(decision.requestId).toBe(proposal.id);
    expect(
      (
        await reviewer.c.post(base + `/requests/${proposal.id}/decision`, {
          headers: reviewer.headers,
          data: submittedDecision,
        })
      ).status(),
    ).toBe(200);
    expect(await count(author.c, approved)).toBe(original + 1);
    for (let n = 0; n < others.length; n++)
      expect(await count(others[n].c, prompt)).toBe(excluded[n]);
    const outcomeMessages = await (
      await author.c.get("/api/v1/notifications")
    ).json();
    expect(
      outcomeMessages.filter(
        (n: { message: string }) => n.message === approved,
      )[0].message,
    ).not.toContain("Private");
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await signIn(page, "amelia");
    await page.getByRole("button", { name: /^Notifications \(/ }).click();
    await expect(dialog.getByText(approved, { exact: true })).toHaveCount(
      original + 1,
    );
    await page.setViewportSize({ width: 390, height: 844 });
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
      path: "docs/evidence/retirement-notifications-mobile.png",
    });
    await page.keyboard.press("Escape");
    await expect(
      page.getByRole("button", { name: /^Notifications \(/ }),
    ).toBeFocused();
  } finally {
    await Promise.all([
      author.c.dispose(),
      reviewer.c.dispose(),
      ...others.map((o) => o.c.dispose()),
    ]);
  }
});
test("notification panel exposes loading, focused retry, empty state and cancels closed reads", async ({
  page,
}) => {
  await signIn(page, "sophia");
  let release: () => void = () => {};
  const held = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route("**/api/v1/notifications", async (route) => {
    await held;
    await route.fulfill({
      status: 503,
      contentType: "application/json",
      body: JSON.stringify({ detail: "Synthetic notification outage" }),
    });
  });
  await page.getByRole("button", { name: /^Notifications \(/ }).click();
  await expect(page.getByRole("status")).toHaveText("Loading notifications…");
  release();
  await expect(
    page.getByRole("alert", { name: "Notification errors" }),
  ).toBeFocused();
  await page.unroute("**/api/v1/notifications");
  await page.route("**/api/v1/notifications", (route) =>
    route.fulfill({ json: [] }),
  );
  await page
    .getByRole("button", { name: "Retry notifications", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "A quiet moment" }),
  ).toBeVisible();
  await expect(page.getByText(/Latest 50 messages/)).toBeVisible();
  await page.unroute("**/api/v1/notifications");
  let finished: () => void = () => {};
  const pending = new Promise<void>((resolve) => {
    finished = resolve;
  });
  await page.route("**/api/v1/notifications", async (route) => {
    await pending;
    await route
      .fulfill({
        json: [
          {
            id: 999999,
            message: "Late closed response",
            recordedAt: new Date().toISOString(),
          },
        ],
      })
      .catch(() => {});
  });
  await page
    .getByRole("button", { name: "Refresh notifications", exact: true })
    .click();
  await expect(page.getByRole("status")).toHaveText("Loading notifications…");
  await page.keyboard.press("Escape");
  finished();
  await expect(
    page.getByRole("dialog", { name: "Notifications", exact: true }),
  ).toHaveCount(0);
  await page.unroute("**/api/v1/notifications");
  await page.getByRole("button", { name: /^Notifications \(/ }).click();
  await expect(
    page.getByRole("button", { name: "Refresh notifications", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("Late closed response", { exact: true }),
  ).toHaveCount(0);
});
