import { test, expect, request, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { checkResponse } from "./support/contract";
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
async function path(hr: Awaited<ReturnType<typeof identity>>) {
  const list = await (
    await hr.c.get("/api/v1/resignations?status=APPROVED&size=100")
  ).json();
  const r = list.items.find(
    (r: { employeeId: string }) => r.employeeId === "E003",
  );
  expect(r, "Prior withdrawn-end fixture required").toBeDefined();
  return `/api/v1/resignations/${r.id}/employment-end`;
}
function date(days: number) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}
const endpoint = "/api/v1/resignations/{id}/employment-end/rescheduling";
test("replacement APIs preserve withdrawn state until approval, deny unsafe inputs and serialize independent decisions", async () => {
  const hr = await identity("amelia"),
    reviewer = await identity("olivia"),
    rival = await identity("olivia"),
    employee = await identity("sophia"),
    outsider = await identity("outsider");
  try {
    const p = await path(hr),
      context = (await checkResponse(
        await hr.c.get(p + "/rescheduling"),
        endpoint,
      )) as {
        currentSchedule: unknown;
        employeeVersion: number;
        history: unknown[];
      };
    expect(context.currentSchedule).toBeNull();
    const c = {
      lastWorkingDate: date(60),
      reason: "Synthetic replacement request for review",
      version: context.employeeVersion,
      idempotencyKey: crypto.randomUUID(),
    };
    for (const user of [employee, outsider]) {
      expect((await user.c.get(p + "/rescheduling")).status()).toBe(403);
      expect(
        (
          await user.c.post(p + "/rescheduling", {
            headers: {
              ...user.headers,
              "X-Role": "HR",
              "X-Tenant-ID": "atlas",
            },
            data: c,
          })
        ).status(),
      ).toBe(403);
    }
    for (const version of [null, -1, 0.5])
      expect(
        (
          await hr.c.post(p + "/rescheduling", {
            headers: hr.headers,
            data: { ...c, version },
          })
        ).status(),
      ).toBe(400);
    for (const lastWorkingDate of [null, date(-1), date(500)])
      expect(
        (
          await hr.c.post(p + "/rescheduling", {
            headers: hr.headers,
            data: { ...c, lastWorkingDate },
          })
        ).status(),
      ).toBe(400);
    const r = (await checkResponse(
      await hr.c.post(p + "/rescheduling", { headers: hr.headers, data: c }),
      endpoint,
      "post",
    )) as { id: string; status: string };
    expect(r.status).toBe("SUBMITTED");
    expect(
      (
        await (
          await hr.c.post(p + "/rescheduling", { headers: hr.headers, data: c })
        ).json()
      ).id,
    ).toBe(r.id);
    expect(
      (
        await hr.c.post(p + "/rescheduling", {
          headers: hr.headers,
          data: { ...c, lastWorkingDate: date(61) },
        })
      ).status(),
    ).toBe(409);
    expect((await (await hr.c.get(p)).json()).phase).toBe("WITHDRAWN");
    expect(
      (
        await hr.c.post(p + "/withdrawals", {
          headers: hr.headers,
          data: {
            reason: "Conflicting review",
            version: c.version,
            idempotencyKey: crypto.randomUUID(),
          },
        })
      ).status(),
    ).toBe(409);
    const reviewPath = p + `/rescheduling/${r.id}/review`,
      review = {
        decision: "REJECTED",
        reason: "Independent rejection retains withdrawn state",
        version: c.version,
        idempotencyKey: crypto.randomUUID(),
      };
    expect(
      (
        await hr.c.post(reviewPath, { headers: hr.headers, data: review })
      ).status(),
    ).toBe(403);
    const other = { ...review, idempotencyKey: crypto.randomUUID() };
    const responses = await Promise.all([
      reviewer.c.post(reviewPath, { headers: reviewer.headers, data: review }),
      rival.c.post(reviewPath, { headers: rival.headers, data: other }),
    ]);
    expect(responses.map((r) => r.status()).sort()).toEqual([200, 409]);
    await checkResponse(
      responses.find((r) => r.status() === 200)!,
      endpoint + "/{rescheduleId}/review",
      "post",
    );
    const winner = responses[0].status() === 200 ? review : other;
    expect(
      (
        await reviewer.c.post(reviewPath, {
          headers: reviewer.headers,
          data: winner,
        })
      ).status(),
    ).toBe(200);
    expect(
      (
        await reviewer.c.post(reviewPath, {
          headers: reviewer.headers,
          data: { ...winner, decision: "APPROVED" },
        })
      ).status(),
    ).toBe(409);
    expect((await (await hr.c.get(p)).json()).phase).toBe("WITHDRAWN");
  } finally {
    await Promise.all(
      [hr, reviewer, rival, employee, outsider].map((x) => x.c.dispose()),
    );
  }
});
async function login(page: Page, user: string) {
  await page.goto("/");
  await page.getByLabel("Workspace account").selectOption(user);
  await page
    .getByRole("button", { name: "Enter workspace", exact: true })
    .click();
  await page.getByRole("button", { name: "Resignations", exact: true }).click();
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
}
test("HR reschedules withdrawn end through independent review with preserved errors, impact, history and a current cutoff", async ({
  page,
}) => {
  await login(page, "amelia");
  const panel = page.getByRole("region", {
    name: "Employment end replacement date",
    exact: true,
  });
  await panel
    .getByRole("button", { name: "Request replacement date", exact: true })
    .click();
  await panel
    .getByRole("button", { name: "Submit replacement request", exact: true })
    .click();
  await expect(panel.getByRole("alert")).toBeFocused();
  await panel
    .getByLabel("Replacement reason (required)", { exact: true })
    .fill(
      "Agreed revised synthetic departure date; independent review required",
    );
  await panel
    .getByRole("button", { name: "Submit replacement request", exact: true })
    .click();
  await expect(
    panel
      .getByText(
        "Choose today through one year from today (provisional scheduling limit).",
        { exact: true },
      )
      .first(),
  ).toBeVisible();
  await panel
    .getByLabel("Replacement last working day (required)", { exact: true })
    .fill(date(70));
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    panel.getByLabel("Replacement reason (required)", { exact: true }),
  ).toBeFocused();
  page.once("dialog", (d) => d.dismiss());
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(
    panel.getByLabel("Replacement last working day (required)", {
      exact: true,
    }),
  ).toHaveValue(date(70));
  await panel
    .getByRole("button", { name: "Submit replacement request", exact: true })
    .click();
  await expect(panel).toContainText("SUBMITTED");
  await expect(panel).toContainText("Another HR employee must review");
  await expect(
    panel.getByRole("button", { name: "Review replacement date", exact: true }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page.getByRole("button", { name: "Sign out", exact: true }).click();
  await login(page, "olivia");
  await panel
    .getByRole("button", { name: "Review replacement date", exact: true })
    .click();
  await expect(panel).toContainText("following day at 00:00 UTC");
  await panel
    .getByLabel("Replacement reason (required)", { exact: true })
    .fill(
      "Independent HR confirms the replacement date and local access impact",
    );
  await panel
    .getByRole("button", { name: "Record replacement decision", exact: true })
    .click();
  await expect(panel).toContainText("One replacement has been approved");
  await expect(panel).toContainText("Reviewed by olivia");
  const end = page.getByRole("region", { name: "Employment end", exact: true });
  await expect(end).toContainText("Replacement date approved");
  await expect(end).toContainText("Scheduled");
  await expect(
    page.getByRole("button", { name: "Assign exit task", exact: true }),
  ).toHaveCount(0);
  await expect(
    end.getByRole("button", { name: "Request end withdrawal", exact: true }),
  ).toHaveCount(0);
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
  await panel.screenshot({ path: "docs/evidence/end-rescheduling-mobile.png" });
  await page.getByRole("button", { name: "Close panel", exact: true }).click();
  await page
    .getByRole("button", { name: "Review resignation E003", exact: true })
    .click();
  await expect(panel).toContainText(
    "Independent HR confirms the replacement date",
  );
});
