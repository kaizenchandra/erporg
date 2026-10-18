import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
const root = "/api/v1/resignations",
  contract = root + "/{id}/withdrawal";
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
  const token = await (await c.get("/api/csrf")).json();
  return { c, headers: { [token.headerName]: token.token } };
}
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  const response = page.waitForResponse(
    (r) =>
      new URL(r.url()).pathname === "/api/login" &&
      r.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  expect((await response).status()).toBe(204);
  await expect(page.getByLabel("Workspace account")).toHaveCount(0);
  await page.getByRole("button", { name: "Resignations", exact: true }).click();
}
function date() {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + 25);
  return d.toISOString().slice(0, 10);
}
test("employee withdraws a pending resignation with private receipt, preserved validation and a fresh-request slot", async ({
  page,
}) => {
  await login(page, "olivia");
  await page
    .getByRole("button", { name: "Request my resignation", exact: true })
    .click();
  const modal = page.getByRole("dialog");
  await modal
    .getByLabel("Proposed last working date (required)", { exact: true })
    .fill(date());
  await modal
    .getByLabel("Resignation reason (required)", { exact: true })
    .fill("Demonstration plan awaiting reconsideration");
  await modal
    .getByRole("button", { name: "Submit resignation request", exact: true })
    .click();
  await expect(modal).toHaveCount(0);
  await page
    .getByRole("button", { name: "Review resignation E005", exact: true })
    .first()
    .click();
  const panel = page.getByRole("region", {
    name: "Resignation withdrawal",
    exact: true,
  });
  await panel
    .getByRole("button", { name: "Withdraw pending resignation", exact: true })
    .click();
  await panel
    .getByRole("button", {
      name: "Confirm withdrawal before review",
      exact: true,
    })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  const reason = panel.getByLabel("Withdrawal reason (required)", {
    exact: true,
  });
  await reason.fill(
    "Plans changed before HR review; keep the original request in history",
  );
  page.once("dialog", (d) => d.dismiss());
  await modal.getByRole("button", { name: "Close panel", exact: true }).click();
  await expect(reason).toHaveValue(
    "Plans changed before HR review; keep the original request in history",
  );
  await page.route(
    "**/api/v1/resignations/*/withdrawal",
    async (route) => {
      expect(route.request().method()).toBe("POST");
      const committed = await route.fetch();
      expect(committed.status()).toBe(200);
      await route.abort("failed");
    },
    { times: 1 },
  );
  await panel
    .getByRole("button", {
      name: "Confirm withdrawal before review",
      exact: true,
    })
    .click();
  await expect(panel.getByRole("alert")).toContainText(/fetch|network|load/i);
  await expect(reason).toHaveValue(
    "Plans changed before HR review; keep the original request in history",
  );
  page.once("dialog", (d) => d.accept());
  await modal.getByRole("button", { name: "Close panel", exact: true }).click();
  await expect(modal).toHaveCount(0);
  await page
    .getByRole("combobox", { name: "Resignation status", exact: true })
    .selectOption("WITHDRAWN");
  await expect(
    page.getByRole("region", { name: "Resignations", exact: true }),
  ).toContainText("Withdrawn");
  await page
    .getByRole("button", { name: "Review resignation E005", exact: true })
    .first()
    .click();
  await expect(panel).toContainText("Plans changed before HR review");
  await expect(
    panel.getByRole("button", {
      name: "Withdraw pending resignation",
      exact: true,
    }),
  ).toHaveCount(0);
  await modal.getByText("Resignation audit (2)", { exact: true }).click();
  await expect(modal).toContainText("SUBMITTED → WITHDRAWN");
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
  await panel.scrollIntoViewIfNeeded();
  await page.screenshot({
    path: "docs/evidence/resignation-withdrawal-mobile.png",
  });
  await modal.getByRole("button", { name: "Close panel", exact: true }).click();
  await page
    .getByRole("button", { name: "Request my resignation", exact: true })
    .click();
  await modal
    .getByLabel("Proposed last working date (required)", { exact: true })
    .fill(date());
  await modal
    .getByLabel("Resignation reason (required)", { exact: true })
    .fill("Fresh request after withdrawal");
  await modal
    .getByRole("button", { name: "Submit resignation request", exact: true })
    .click();
  await expect(modal).toHaveCount(0);
  await page
    .getByRole("combobox", { name: "Resignation status", exact: true })
    .selectOption("SUBMITTED");
  await page
    .getByRole("button", { name: "Review resignation E005", exact: true })
    .first()
    .click();
  await panel
    .getByRole("button", { name: "Withdraw pending resignation", exact: true })
    .click();
  await panel
    .getByLabel("Withdrawal reason (required)", { exact: true })
    .fill("Withdraw fresh demonstration request");
  await panel
    .getByRole("button", {
      name: "Confirm withdrawal before review",
      exact: true,
    })
    .click();
  await expect(modal).toHaveCount(0);
  expect((await page.request.get("/api/v1/session")).status()).toBe(200);
});
test("withdrawal contracts enforce ownership/replay and racing HR decisions produce one terminal audit", async () => {
  const owner = await identity("olivia"),
    hr = await identity("amelia"),
    other = await identity("sophia"),
    foreign = await identity("outsider");
  try {
    const before = await (await owner.c.get("/api/v1/employees/E005")).json();
    for (let attempt = 0; attempt < 2; attempt++) {
      const submit = {
        lastWorkingDate: date(),
        reason: "Race withdrawal and independent rejection",
        employeeVersion: before.version,
        idempotencyKey: crypto.randomUUID(),
      };
      const made = await owner.c.post(root, {
        headers: owner.headers,
        data: submit,
      });
      expect(made.status()).toBe(200);
      const r = await made.json();
      const path = `${root}/${r.id}/withdrawal`;
      await checkResponse(await owner.c.get(path), contract, "get");
      const command = {
        reason: "Cancel this unreviewed request",
        version: 0,
        idempotencyKey: crypto.randomUUID(),
      };
      for (const actor of [hr, other, foreign]) {
        expect(
          (
            await actor.c.post(path, {
              headers: {
                ...actor.headers,
                "X-Role": "EMPLOYEE",
                "X-Tenant": "atlas",
              },
              data: command,
            })
          ).status(),
        ).toBe(403);
      }
      for (const actor of [other, foreign])
        expect((await actor.c.get(path)).status()).toBe(403);
      for (const version of [null, -1, 0.5])
        expect(
          (
            await owner.c.post(path, {
              headers: owner.headers,
              data: { ...command, version },
            })
          ).status(),
        ).toBe(400);
      expect(
        (
          await owner.c.post(path, {
            headers: owner.headers,
            data: { ...command, version: 1 },
          })
        ).status(),
      ).toBe(409);
      expect(
        (
          await owner.c.post(path, {
            headers: owner.headers,
            data: { ...command, reason: " " },
          })
        ).status(),
      ).toBe(400);
      const decision = {
        decision: "REJECTED",
        comment: "Independent rejection raced withdrawal",
        version: 0,
        idempotencyKey: crypto.randomUUID(),
      };
      const results = await Promise.all([
        owner.c.post(path, { headers: owner.headers, data: command }),
        hr.c.post(`${root}/${r.id}/decision`, {
          headers: hr.headers,
          data: decision,
        }),
      ]);
      expect(results.map((x) => x.status()).sort()).toEqual([200, 409]);
      const receipt = await owner.c.get(path);
      await checkResponse(receipt, contract, "get");
      const context = await receipt.json();
      if (results[0].status() === 200) {
        await checkResponse(results[0], contract, "post");
        expect(context.receipt.reason).toBe(command.reason);
        expect(
          (
            await owner.c.post(path, { headers: owner.headers, data: command })
          ).status(),
        ).toBe(200);
        expect(
          (
            await owner.c.post(path, {
              headers: owner.headers,
              data: { ...command, reason: "Changed payload" },
            })
          ).status(),
        ).toBe(409);
      } else expect(context.receipt).toBeNull();
      const history = await (
        await owner.c.get(`${root}/${r.id}/history`)
      ).json();
      expect(history).toHaveLength(2);
      expect(history[1].newStatus).toBe(
        results[0].status() === 200 ? "WITHDRAWN" : "REJECTED",
      );
      const list = await (await owner.c.get(root)).json();
      expect(list.items.find((x: { id: string }) => x.id === r.id).status).toBe(
        history[1].newStatus,
      );
      const after = await (await owner.c.get("/api/v1/employees/E005")).json();
      expect(after.version).toBe(before.version);
      expect(after.status).toBe(before.status);
    }
  } finally {
    await Promise.all([owner, hr, other, foreign].map((x) => x.c.dispose()));
  }
});
