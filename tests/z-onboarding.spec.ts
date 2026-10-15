import {
  test,
  expect,
  request,
  type APIRequestContext,
} from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
async function approvedHire(
  author: Awaited<ReturnType<typeof identity>>,
  reviewer: Awaited<ReturnType<typeof identity>>,
  future = false,
) {
  const date = new Date(Date.now() + (future ? 86400000 : 0))
    .toISOString()
    .slice(0, 10);
  const submitted = await author.client.post("/api/v1/hiring/requests", {
    headers: author.headers,
    data: {
      name: `Zoe Onboarding ${Date.now()}`,
      email: `onboarding-${crypto.randomUUID()}@example.test`,
      phone: "+1 202 555 8888",
      title: "Analyst",
      department: "Design",
      location: "London",
      managerId: "E002",
      startDate: date,
      reason: "Synthetic onboarding acceptance",
      idempotencyKey: crypto.randomUUID(),
    },
  });
  expect(submitted.status()).toBe(200);
  const hire = await submitted.json();
  const approved = await reviewer.client.post(
    `/api/v1/hiring/requests/${hire.id}/decision`,
    {
      headers: reviewer.headers,
      data: { decision: "APPROVED", comment: "Independent hiring review" },
    },
  );
  expect(approved.status()).toBe(200);
  return approved.json();
}
test("HR confirms completion; Active profile and employment history persist through UI", async ({
  page,
}) => {
  const author = await identity("amelia"),
    reviewer = await identity("olivia");
  try {
    const hire = await approvedHire(author, reviewer);
    await page.goto("/");
    await page.getByLabel("Workspace account").selectOption("olivia");
    await page.getByRole("button", { name: "Enter workspace" }).click();
    await expect(page.getByRole("table")).toBeVisible();
    await page.getByRole("button", { name: "Hiring", exact: true }).click();
    await page.getByLabel("Hiring status").selectOption("APPROVED");
    const card = page.locator(".request-card").filter({ hasText: hire.name });
    await card
      .getByRole("button", { name: "Complete onboarding", exact: true })
      .click();
    await page
      .getByLabel("Completion reason")
      .fill("Joining documents and readiness verified");
    await expect(
      page.getByRole("button", { name: "Confirm Active status" }),
    ).toBeVisible();
    const axe = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
      .analyze();
    expect(axe.violations).toEqual([]);
    await page.getByRole("button", { name: "Confirm Active status" }).click();
    await expect(
      page.getByText("Onboarding completed. Employee status is Active."),
    ).toBeVisible();
    await card
      .getByRole("button", { name: "Open employee", exact: true })
      .click();
    const dialog = page.getByRole("dialog");
    await expect(dialog.getByText("Active", { exact: true })).toBeVisible();
    await expect(
      dialog.getByText("Onboarding → Active", { exact: true }),
    ).toBeVisible();
    await expect(
      dialog.getByText("Joining documents and readiness verified", {
        exact: true,
      }),
    ).toBeVisible();
    await page.screenshot({
      path: "docs/evidence/completed-onboarding-profile.png",
      fullPage: true,
    });
  } finally {
    await Promise.all([author.client.dispose(), reviewer.client.dispose()]);
  }
});
test("completion API rejects early/stale/non-HR; concurrent winner and replay remain stable", async () => {
  const contexts: APIRequestContext[] = [];
  try {
    const author = await identity("amelia"),
      reviewer = await identity("olivia"),
      manager = await identity("marcus");
    contexts.push(author.client, reviewer.client, manager.client);
    const hire = await approvedHire(author, reviewer);
    const path = `/api/v1/hiring/requests/${hire.id}/complete-onboarding`;
    const payload = {
      reason: "Private joining readiness",
      version: 0,
      idempotencyKey: crypto.randomUUID(),
    };
    expect(
      (
        await manager.client.post(path, {
          headers: manager.headers,
          data: payload,
        })
      ).status(),
    ).toBe(403);
    expect(
      (
        await author.client.post(path, {
          headers: author.headers,
          data: {
            reason: payload.reason,
            idempotencyKey: payload.idempotencyKey,
          },
        })
      ).status(),
    ).toBe(400);
    expect(
      (
        await author.client.post(path, {
          headers: author.headers,
          data: { ...payload, version: 7 },
        })
      ).status(),
    ).toBe(409);
    const results = await Promise.all(
      [author, reviewer].map((user) =>
        user.client.post(path, {
          headers: user.headers,
          data: { ...payload, idempotencyKey: crypto.randomUUID() },
        }),
      ),
    );
    expect(results.map((r) => r.status()).sort()).toEqual([200, 409]);
    await checkResponse(
      results.find((r) => r.status() === 200)!,
      "/api/v1/hiring/requests/{id}/complete-onboarding",
      "post",
    );
    const historyPath = `/api/v1/hiring/requests/employees/${hire.employeeId}/employment-history`;
    const history = (await checkResponse(
      await author.client.get(historyPath),
      "/api/v1/hiring/requests/employees/{id}/employment-history",
    )) as { reason: string | null }[];
    expect(history).toHaveLength(1);
    expect(history[0].reason).toBe(payload.reason);
    const managerHistory = await (await manager.client.get(historyPath)).json();
    expect(managerHistory[0].reason).toBeNull();
    expect(JSON.stringify(managerHistory)).not.toContain(payload.reason);
    const future = await approvedHire(author, reviewer, true);
    const futurePath = `/api/v1/hiring/requests/${future.id}/complete-onboarding`;
    const futureResult = await author.client.post(futurePath, {
      headers: author.headers,
      data: payload,
    });
    expect(futureResult.status()).toBe(409);
    expect(
      (
        await (
          await author.client.get(`/api/v1/employees/${future.employeeId}`)
        ).json()
      ).status,
    ).toBe("Onboarding");
    const replayHire = await approvedHire(author, reviewer);
    const replayPath = `/api/v1/hiring/requests/${replayHire.id}/complete-onboarding`;
    const first = await author.client.post(replayPath, {
      headers: author.headers,
      data: payload,
    });
    expect(first.status()).toBe(200);
    const body = await first.json();
    const replay = await author.client.post(replayPath, {
      headers: author.headers,
      data: payload,
    });
    expect(replay.status()).toBe(200);
    expect(await replay.json()).toEqual(body);
  } finally {
    await Promise.all(contexts.map((client) => client.dispose()));
  }
});
