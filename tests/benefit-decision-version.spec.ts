import { test, expect, request } from "@playwright/test";
import type { SelectionPage } from "../features/benefits/selections";

test("fractional benefit decision is rejected by the real API and the preserved form recovers with an exact version", async ({
  page,
}) => {
  const client = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  async function loginApi(user: string) {
    let csrf = (await (await client.get("/api/csrf")).json()) as {
      headerName: string;
      token: string;
    };
    expect(
      (
        await client.post("/api/login", {
          headers: { [csrf.headerName]: csrf.token },
          form: { username: user, password: "atlas-demo" },
        })
      ).status(),
    ).toBe(204);
    csrf = (await (await client.get("/api/csrf")).json()) as {
      headerName: string;
      token: string;
    };
    return { [csrf.headerName]: csrf.token };
  }
  try {
    const code = `BH-${crypto.randomUUID().slice(0, 8).toUpperCase()}`;
    let headers = await loginApi("amelia");
    expect(
      (
        await client.post("/api/v1/benefits/plans", {
          headers,
          data: {
            code,
            name: code,
            currency: "GBP",
            monthlyContribution: "15.50",
            minimumServiceDays: "0",
            effectiveFrom: new Date().toISOString().slice(0, 10),
            reason: "Synthetic BH browser fixture",
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    headers = await loginApi("sophia");
    const submitted = await client.post("/api/v1/benefits/selections", {
      headers,
      data: {
        planCode: code,
        dependants: [],
        reason: "Synthetic BH exact-version request",
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(submitted.status()).toBe(200);
    const selected = (await submitted.json()) as { id: string };
    await page.goto("/");
    await page.getByLabel("Workspace account").selectOption("amelia");
    await page
      .getByRole("button", { name: "Enter workspace", exact: true })
      .click();
    await expect(
      page.getByRole("button", { name: "Sign out", exact: true }),
    ).toBeVisible();
    await page.getByRole("button", { name: /^Approvals/ }).click();
    await page
      .getByRole("button", { name: "Benefit selections", exact: true })
      .click();
    const panel = page.getByRole("region", {
      name: "Benefit selection approval queue",
      exact: true,
    });
    const item = panel.locator("article").filter({ hasText: code });
    await item.getByRole("button", { name: /^Review benefit request/ }).click();
    const reason = page.getByRole("textbox", {
      name: /^Benefit selection reason/,
    });
    await reason.fill("Synthetic BH preserved independent review");
    const endpoint = `/api/v1/benefits/selections/${selected.id}/decision`;
    // Alter one outgoing number, then forward to the actual API. No response is mocked.
    await page.route(
      `**${endpoint}`,
      async (route) => {
        const body = route.request().postDataJSON() as Record<string, unknown>;
        await route.continue({
          postData: JSON.stringify({ ...body, version: 0.5 }),
        });
      },
      { times: 1 },
    );
    const invalid = page.waitForResponse(
      (r) => r.url().endsWith(endpoint) && r.request().method() === "POST",
    );
    await page
      .getByRole("button", { name: "Confirm benefit decision", exact: true })
      .click();
    expect((await invalid).status()).toBe(400);
    const error = page.getByRole("alert", {
      name: "Benefit selection errors",
      exact: true,
    });
    await expect(error).toBeFocused();
    await expect(error).toContainText("exact nonnegative selection version");
    await expect(reason).toHaveValue(
      "Synthetic BH preserved independent review",
    );
    const pending = (await (
      await client.get(
        "/api/v1/benefits/selection-inbox?scope=OWN&status=PENDING&size=50",
      )
    ).json()) as SelectionPage;
    const unchanged = pending.items.find((s) => s.id === selected.id);
    expect(unchanged).toMatchObject({
      status: "PENDING",
      version: 0,
      handoffStatus: null,
    });
    expect(unchanged?.history).toHaveLength(1);
    const valid = page.waitForResponse(
      (r) => r.url().endsWith(endpoint) && r.request().method() === "POST",
    );
    await page
      .getByRole("button", { name: "Confirm benefit decision", exact: true })
      .click();
    expect((await valid).status()).toBe(200);
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await expect(item).toHaveCount(0);
    await panel
      .getByRole("combobox", { name: /^Benefit queue status/ })
      .selectOption("APPROVED");
    await expect(item).toContainText("PENDING_PAYROLL");
    const approved = (await (
      await client.get(
        "/api/v1/benefits/selection-inbox?scope=OWN&status=APPROVED&size=50",
      )
    ).json()) as SelectionPage;
    const saved = approved.items.find((s) => s.id === selected.id);
    expect(saved).toMatchObject({
      status: "APPROVED",
      version: 1,
      handoffStatus: "PENDING_PAYROLL",
    });
    expect(saved?.history).toHaveLength(2);
  } finally {
    await client.dispose();
  }
});
