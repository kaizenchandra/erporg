import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
async function identity(user: string) {
  const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  const token = await (await c.get("/api/csrf")).json();
  expect(
    (
      await c.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [token.headerName]: token.token },
      })
    ).status(),
  ).toBe(204);
  const csrf = await (await c.get("/api/csrf")).json();
  return { c, headers: { [csrf.headerName]: csrf.token } };
}
async function task(hr: Awaited<ReturnType<typeof identity>>) {
  const rows = await (
    await hr.c.get("/api/v1/resignations?status=APPROVED&size=100")
  ).json();
  const r = rows.items.find(
    (r: { employeeId: string }) => r.employeeId === "E003",
  );
  expect(r).toBeDefined();
  const path = `/api/v1/resignations/${r.id}/tasks`,
    list = await (await hr.c.get(path)).json();
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + 10);
  const command = {
    assigneeId: "E002",
    title: `Managed task ${crypto.randomUUID().slice(0, 8)}`,
    instructions: "Synthetic operational instructions",
    dueDate: d.toISOString().slice(0, 10),
    reason: "Coordinate approved exit",
    version: list.version,
    idempotencyKey: crypto.randomUUID(),
  };
  const response = await hr.c.post(path, {
    headers: hr.headers,
    data: command,
  });
  expect(response.status()).toBe(200);
  return { task: (await response.json()).task, command, path };
}
async function login(page: Page, user: string, tab: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page.getByRole("button", { name: "Enter workspace" }).click();
  await expect(
    page.getByRole("heading", { name: "People", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: tab, exact: true }).click();
}
async function logout(page: Page) {
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await expect(page.getByLabel("Workspace account")).toBeVisible();
}
test("HR reassigns then cancels with private history; former assignee loses access and cancelled mobile task cannot complete", async ({
  page,
}) => {
  const hr = await identity("amelia"),
    old = await identity("marcus"),
    current = await identity("sophia");
  try {
    const fixture = await task(hr),
      t = fixture.task,
      cardName = `Exit task ${t.title}`;
    await login(page, "amelia", "Resignations");
    await page
      .getByRole("button", { name: "Review resignation E003", exact: true })
      .click();
    const region = page.getByRole("region", {
        name: "Case exit tasks",
        exact: true,
      }),
      modal = page.getByRole("dialog");
    await region
      .getByRole("article", { name: cardName, exact: true })
      .getByRole("button", { name: `Manage task ${t.title}`, exact: true })
      .click();
    const form = page.getByRole("form", {
      name: "Manage exit task",
      exact: true,
    });
    await form
      .getByRole("button", { name: "Confirm task reassignment", exact: true })
      .click();
    await expect(form.getByRole("alert")).toBeFocused();
    expect(
      (
        await new AxeBuilder({ page })
          .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
          .analyze()
      ).violations,
    ).toEqual([]);
    await form
      .getByLabel("New assigned employee ID (required)", { exact: true })
      .fill("E003");
    await form
      .getByLabel("Task management reason (required)", { exact: true })
      .fill("Private HR reassignment context");
    page.once("dialog", (d) => d.dismiss());
    await page.keyboard.press("Escape");
    await expect(form).toBeVisible();
    await expect(
      form.getByLabel("New assigned employee ID (required)", { exact: true }),
    ).toHaveValue("E003");
    await form
      .getByRole("button", { name: "Confirm task reassignment", exact: true })
      .click();
    await expect(form).toHaveCount(0);
    await expect(
      region.getByRole("heading", {
        name: "Exit coordination tasks",
        exact: true,
      }),
    ).toBeFocused();
    expect(
      (await old.c.get(`/api/v1/exit-tasks/${t.id}/history`)).status(),
    ).toBe(403);
    const replay = await hr.c.post(fixture.path, {
      headers: hr.headers,
      data: fixture.command,
    });
    expect(replay.status()).toBe(200);
    expect((await replay.json()).task.assigneeId).toBe("E003");
    await modal
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
    await logout(page);
    await login(page, "sophia", "My exit tasks");
    const own = page.getByRole("region", {
      name: "My exit tasks",
      exact: true,
    });
    await expect(
      own.getByRole("article", { name: cardName, exact: true }),
    ).toBeVisible();
    await expect(own).not.toContainText("Private HR reassignment context");
    expect(
      (
        await current.c.get(`/api/v1/exit-tasks/${t.id}/management-history`)
      ).status(),
    ).toBe(403);
    await logout(page);
    await login(page, "amelia", "Resignations");
    await page
      .getByRole("button", { name: "Review resignation E003", exact: true })
      .click();
    await region
      .getByRole("article", { name: cardName, exact: true })
      .getByRole("button", { name: `Manage task ${t.title}`, exact: true })
      .click();
    await form.getByLabel("Task management action").selectOption("CANCEL");
    await form
      .getByLabel("Task management reason (required)", { exact: true })
      .fill("Private HR cancellation context");
    await expect(form).toContainText("It cannot be reopened");
    await form
      .getByRole("button", { name: "Confirm task cancellation", exact: true })
      .click();
    await expect(form).toHaveCount(0);
    const managed = region.getByRole("article", {
      name: cardName,
      exact: true,
    });
    await expect(managed).toContainText("CANCELLED");
    await managed
      .getByText(`Private management history for ${t.title}`, { exact: true })
      .click();
    await expect(managed).toContainText("Private HR reassignment context");
    await expect(managed).toContainText("Private HR cancellation context");
    await managed.screenshot({ path: "docs/evidence/task-management-hr.png" });
    await modal
      .getByRole("button", { name: "Close panel", exact: true })
      .click();
    await logout(page);
    await page.setViewportSize({ width: 390, height: 844 });
    await login(page, "sophia", "My exit tasks");
    await own.getByLabel("Exit task status").selectOption("CANCELLED");
    const cancelled = own.getByRole("article", { name: cardName, exact: true });
    await expect(cancelled).toContainText("Cancelled by HR");
    await expect(
      cancelled.getByRole("button", { name: /Complete task/ }),
    ).toHaveCount(0);
    await expect(own).not.toContainText("Private HR cancellation context");
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
    await cancelled.screenshot({
      path: "docs/evidence/task-cancelled-mobile.png",
    });
  } finally {
    await Promise.all([hr, old, current].map((x) => x.c.dispose()));
  }
});
test("live management contracts, exact versions and races with old-assignee completion preserve one winner", async () => {
  const hr = await identity("amelia"),
    old = await identity("marcus"),
    current = await identity("sophia"),
    outsider = await identity("outsider");
  try {
    for (const action of ["REASSIGN", "CANCEL"]) {
      const { task: t } = await task(hr),
        path = `/api/v1/exit-tasks/${t.id}/management`,
        complete = `/api/v1/exit-tasks/${t.id}/complete`;
      const command = {
        action,
        assigneeId: action === "REASSIGN" ? "E003" : null,
        reason: "Private race reason",
        version: 0,
        idempotencyKey: crypto.randomUUID(),
      };
      for (const version of [null, 0.5, -1])
        expect(
          (
            await hr.c.post(path, {
              headers: hr.headers,
              data: { ...command, version },
            })
          ).status(),
        ).toBe(400);
      for (const user of [old, current, outsider])
        expect(
          (
            await user.c.post(path, {
              headers: {
                ...user.headers,
                "X-Tenant-ID": "atlas",
                "X-Role": "HR",
              },
              data: command,
            })
          ).status(),
        ).toBe(403);
      const results = await Promise.all([
        hr.c.post(path, { headers: hr.headers, data: command }),
        old.c.post(complete, {
          headers: old.headers,
          data: {
            comment: "Concurrent manual attestation",
            version: 0,
            idempotencyKey: crypto.randomUUID(),
          },
        }),
      ]);
      expect(results.filter((r) => r.status() === 200)).toHaveLength(1);
      expect([403, 409]).toContain(
        results.find((r) => r.status() !== 200)!.status(),
      );
      if (results[0].status() === 200) {
        await checkResponse(
          results[0],
          "/api/v1/exit-tasks/{id}/management",
          "post",
        );
        const replay = await hr.c.post(path, {
          headers: hr.headers,
          data: command,
        });
        expect(replay.status()).toBe(200);
        expect((await replay.json()).ordinal).toBe(1);
        expect(
          (
            await hr.c.post(path, {
              headers: hr.headers,
              data: { ...command, reason: "Conflicting replay" },
            })
          ).status(),
        ).toBe(409);
        if (action === "REASSIGN") {
          expect(
            (await old.c.get(`/api/v1/exit-tasks/${t.id}/history`)).status(),
          ).toBe(403);
          const done = await current.c.post(complete, {
            headers: current.headers,
            data: {
              comment: "New assignee attestation",
              version: 1,
              idempotencyKey: crypto.randomUUID(),
            },
          });
          expect(done.status()).toBe(200);
          await checkResponse(done, "/api/v1/exit-tasks/{id}/complete", "post");
        } else
          expect(
            (
              await old.c.post(complete, {
                headers: old.headers,
                data: {
                  comment: "Cancelled",
                  version: 1,
                  idempotencyKey: crypto.randomUUID(),
                },
              })
            ).status(),
          ).toBe(409);
      } else
        await checkResponse(
          results[1],
          "/api/v1/exit-tasks/{id}/complete",
          "post",
        );
      const journal = (await checkResponse(
        await hr.c.get(`/api/v1/exit-tasks/${t.id}/management-history`),
        "/api/v1/exit-tasks/{id}/management-history",
      )) as unknown[];
      expect(journal).toHaveLength(results[0].status() === 200 ? 1 : 0);
      expect(
        (
          await outsider.c.get(`/api/v1/exit-tasks/${t.id}/management-history`)
        ).status(),
      ).toBe(403);
    }
    const fixed = await task(hr);
    const fixedPath = `/api/v1/exit-tasks/${fixed.task.id}/management`;
    expect(
      (
        await hr.c.post(fixedPath, {
          headers: hr.headers,
          data: {
            action: "REASSIGN",
            assigneeId: "E003",
            reason: "Deterministic reassignment",
            version: 0,
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(200);
    const fixedCancel = await hr.c.post(fixedPath, {
      headers: hr.headers,
      data: {
        action: "CANCEL",
        assigneeId: null,
        reason: "Deterministic cancellation",
        version: 1,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(fixedCancel.status()).toBe(200);
    await checkResponse(
      fixedCancel,
      "/api/v1/exit-tasks/{id}/management",
      "post",
    );
    const own = (await checkResponse(
      await current.c.get("/api/v1/exit-tasks?status=CANCELLED"),
      "/api/v1/exit-tasks",
    )) as { items: unknown[] };
    expect(own.items.length).toBeGreaterThanOrEqual(1);
  } finally {
    await Promise.all([hr, old, current, outsider].map((x) => x.c.dispose()));
  }
});
