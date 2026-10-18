import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
const employee = "E-RETURN-TEST",
  path = `/api/v1/employees/${employee}/rehire/access`,
  endpoint = "/api/v1/employees/{employee}/rehire/access";
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
async function login(page: Page) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption("amelia");
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await expect(page.getByLabel("Workspace account")).toHaveCount(0);
}
test("revocation context and command enforce scope, exact versions and named grant without changing access", async () => {
  const hr = await identity("amelia"),
    employeeSession = await identity("returning"),
    manager = await identity("marcus"),
    other = await identity("outsider");
  try {
    const response = await hr.c.get(path);
    await checkResponse(response, endpoint, "get");
    const current = await response.json();
    expect(current.accessStatus).toBe("GRANTED");
    const command = {
      activationId: current.activationId,
      reason: "Synthetic security suspension",
      version: current.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const session of [employeeSession, manager, other]) {
      expect(
        (
          await session.c.get(path, {
            headers: { "X-Role": "HR", "X-Tenant": "atlas" },
          })
        ).status(),
      ).toBe(403);
      expect(
        (
          await session.c.post(path + "/revoke", {
            headers: { ...session.headers, "X-Role": "HR" },
            data: command,
          })
        ).status(),
      ).toBe(403);
    }
    for (const version of [null, 0.5, -1])
      expect(
        (
          await hr.c.post(path + "/revoke", {
            headers: hr.headers,
            data: { ...command, version },
          })
        ).status(),
      ).toBe(400);
    expect(
      (
        await hr.c.post(path + "/revoke", {
          headers: hr.headers,
          data: { ...command, version: 0 },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await hr.c.post(path + "/revoke", {
          headers: hr.headers,
          data: { ...command, activationId: "wrong-grant" },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await hr.c.post(path + "/revoke", {
          headers: hr.headers,
          data: { ...command, reason: " " },
        })
      ).status(),
    ).toBe(400);
    expect(
      (await hr.c.get("/api/v1/employees/E001/rehire/access")).status(),
    ).toBe(409);
    expect(
      (await hr.c.get("/api/v1/employees/E900/rehire/access")).status(),
    ).toBe(403);
    expect((await (await hr.c.get(path)).json()).accessStatus).toBe("GRANTED");
  } finally {
    await Promise.all(
      [hr, employeeSession, manager, other].map((s) => s.c.dispose()),
    );
  }
});
test("HR confirms immediate revocation with private audit; held sessions and new logins are denied while employment stays Active", async ({
  page,
}) => {
  const held = await identity("returning");
  try {
    await login(page);
    await page.getByLabel("Search employees").fill(employee);
    await page
      .getByRole("button", { name: "View Jordan Lee", exact: true })
      .click();
    const panel = page.getByRole("region", {
      name: "Current rehire local access",
      exact: true,
    });
    await expect(panel).toContainText("GRANTED");
    await panel
      .getByRole("button", { name: "Revoke local access", exact: true })
      .click();
    await panel
      .getByRole("button", {
        name: "Confirm immediate revocation",
        exact: true,
      })
      .click();
    await expect(panel.getByRole("alert")).toBeFocused();
    await panel
      .getByLabel("Revocation reason (required)", { exact: true })
      .fill(
        "Security incident review: suspend the local grant without ending employment",
      );
    page.once("dialog", (d) => d.dismiss());
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toBeVisible();
    await expect(
      panel.getByLabel("Revocation reason (required)", { exact: true }),
    ).toHaveValue(
      "Security incident review: suspend the local grant without ending employment",
    );
    await expect(panel).toContainText("without a second approval");
    const sent = page.waitForRequest(
      (r) => r.method() === "POST" && r.url().endsWith(path + "/revoke"),
    );
    const response = page.waitForResponse(
      (r) =>
        r.request().method() === "POST" && r.url().endsWith(path + "/revoke"),
    );
    await panel
      .getByRole("button", {
        name: "Confirm immediate revocation",
        exact: true,
      })
      .click();
    const body = (await sent).postDataJSON() as {
      activationId: string;
      reason: string;
      version: number;
      idempotencyKey: string;
    };
    const outcome = await response;
    await checkResponse(outcome, endpoint + "/revoke", "post");
    const original = await outcome.json();
    await expect(panel).toContainText("REVOKED");
    await expect(panel).toContainText("Employment remains Active");
    await expect(panel).toContainText("Local access revoked by amelia");
    await expect(panel).toContainText(body.reason);
    await expect(
      panel.getByRole("button", {
        name: "Confirm immediate revocation",
        exact: true,
      }),
    ).toHaveCount(0);
    expect(
      (
        await held.c.get("/api/v1/session", { headers: { "X-Role": "HR" } })
      ).status(),
    ).toBe(401);
    const csrf = await (await page.request.get("/api/csrf")).json(),
      headers = { [csrf.headerName]: csrf.token };
    const replay = await page.request.post(path + "/revoke", {
      headers,
      data: body,
    });
    expect(replay.status()).toBe(200);
    expect(await replay.json()).toEqual(original);
    expect(
      (
        await page.request.post(path + "/revoke", {
          headers,
          data: { ...body, reason: "Different reason" },
        })
      ).status(),
    ).toBe(409);
    expect(
      (
        await page.request.post(path + "/revoke", {
          headers,
          data: { ...body, idempotencyKey: crypto.randomUUID() },
        })
      ).status(),
    ).toBe(409);
    const p = await (
      await page.request.get(`/api/v1/employees/${employee}`)
    ).json();
    expect(p.status).toBe("Active");
    expect(p.version).toBe(body.version + 1);
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
    await panel
      .getByText("Local access revoked by amelia", { exact: false })
      .scrollIntoViewIfNeeded();
    await page.screenshot({
      path: "docs/evidence/rehire-revocation-mobile.png",
    });
    for (const user of ["returning", "returning-alias"]) {
      const c = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
      try {
        const t = await (await c.get("/api/csrf")).json();
        expect(
          (
            await c.post("/api/login", {
              form: { username: user, password: "atlas-demo" },
              headers: { [t.headerName]: t.token },
            })
          ).status(),
        ).toBe(401);
      } finally {
        await c.dispose();
      }
    }
    await page.reload();
    await page.getByLabel("Search employees").fill(employee);
    await page
      .getByRole("button", {
        name: "Jordan Lee Returning engineer Active",
        exact: true,
      })
      .click();
    await expect(panel).toContainText("REVOKED");
  } finally {
    await held.c.dispose();
  }
});
