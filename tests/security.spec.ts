import {
  test,
  expect,
  request,
  type APIRequestContext,
} from "@playwright/test";
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
  const fresh = await (await c.get("/api/csrf")).json();
  return { c, headers: { [fresh.headerName]: fresh.token } };
}
async function employee(c: APIRequestContext, id: string) {
  return (await (await c.get("/api/v1/employees")).json()).find(
    (e: { id: string }) => e.id === id,
  );
}
test("direct API denies unauthenticated, cross-tenant and forged field access; logout revokes session", async () => {
  const anon = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  expect((await anon.get("/api/v1/employees")).status()).toBe(401);
  const { c, headers } = await identity("sophia");
  expect((await c.get("/api/v1/employees/E006/history")).status()).toBe(403);
  expect((await c.get("/api/v1/employees/E900/history")).status()).toBe(403);
  expect((await c.post("/api/v1/requests", { data: {} })).status()).toBe(403);
  const malformed = await c.post("/api/v1/requests", { headers, data: {} });
  expect(malformed.status()).toBe(400);
  expect((await malformed.json()).correlationId).toBe(
    malformed.headers()["x-correlation-id"],
  );
  const e = await employee(c, "E003");
  const invalid = await c.post("/api/v1/requests", {
    headers,
    data: {
      employeeId: e.id,
      field: "phone",
      newValue: "private-invalid-value",
      reason: " ",
      effectiveDate: null,
      version: e.version,
      idempotencyKey: crypto.randomUUID(),
    },
  });
  expect(invalid.status()).toBe(400);
  const validation = await invalid.json();
  expect(Object.keys(validation.fieldErrors).sort()).toEqual([
    "effectiveDate",
    "newValue",
    "reason",
  ]);
  expect(JSON.stringify(validation)).not.toContain("private-invalid-value");
  expect((await employee(c, "E003")).version).toBe(e.version);
  expect(
    (
      await c.post("/api/v1/requests", {
        headers,
        data: {
          employeeId: e.id,
          field: "title",
          newValue: "CEO",
          effectiveDate: new Date().toISOString().slice(0, 10),
          reason: "forged",
          version: e.version,
          idempotencyKey: crypto.randomUUID(),
        },
      })
    ).status(),
  ).toBe(403);
  const other = await identity("outsider");
  expect(
    (
      await (
        await other.c.get("/api/v1/employees", {
          headers: { "X-Tenant-ID": "atlas" },
        })
      ).json()
    ).map((e: { id: string }) => e.id),
  ).toEqual(["E900"]);
  expect((await c.post("/api/logout", { headers })).status()).toBe(204);
  expect((await c.get("/api/v1/employees")).status()).toBe(401);
  await Promise.all([c.dispose(), other.c.dispose(), anon.dispose()]);
});
test("duplicate submit is stable; concurrent reviewers produce one committed approval; future value not early", async () => {
  const self = await identity("marcus"),
    hr = await identity("amelia");
  const e = await employee(self.c, "E002");
  const future = new Date(Date.now() + 30 * 86400000)
    .toISOString()
    .slice(0, 10);
  const payload = {
    employeeId: e.id,
    field: "phone",
    newValue: "+44 7700 " + Date.now().toString().slice(-6),
    effectiveDate: future,
    reason: "Concurrent API acceptance",
    version: e.version,
    idempotencyKey: crypto.randomUUID(),
  };
  const first = await self.c.post("/api/v1/requests", {
    headers: self.headers,
    data: payload,
  });
  expect(first.status()).toBe(200);
  const r = await first.json();
  const repeat = await self.c.post("/api/v1/requests", {
    headers: self.headers,
    data: payload,
  });
  expect((await repeat.json()).id).toBe(r.id);
  expect(
    (
      await self.c.post("/api/v1/requests", {
        headers: self.headers,
        data: { ...payload, newValue: "+44 7700 999999" },
      })
    ).status(),
  ).toBe(409);
  expect(
    (
      await self.c.post(`/api/v1/requests/${r.id}/decision`, {
        headers: self.headers,
        data: { decision: "APPROVED", comment: "self-approval" },
      })
    ).status(),
  ).toBe(403);
  const replies = await Promise.all(
    [1, 2].map(() =>
      hr.c.post(`/api/v1/requests/${r.id}/decision`, {
        headers: hr.headers,
        data: {
          decision: "APPROVED",
          comment: "Independent concurrent review",
        },
      }),
    ),
  );
  expect(replies.map((x) => x.status()).sort()).toEqual([200, 409]);
  expect((await employee(self.c, "E002")).phone).toBe(e.phone);
  const audit = await (await hr.c.get("/api/v1/employees/E002/history")).json();
  expect(
    audit.filter(
      (x: { reason: string; action: string; newValue: string }) =>
        x.reason === "Independent concurrent review" &&
        x.action === "APPROVED" &&
        x.newValue === payload.newValue,
    ),
  ).toHaveLength(1);
  await self.c.dispose();
  await hr.c.dispose();
});

test("directory API bounds pages, rejects invalid sorting and scopes totals", async () => {
  const hr = await identity("amelia"),
    self = await identity("sophia");
  const result = await hr.c.get(
    "/api/v1/directory?size=5&page=1&direction=desc",
  );
  expect(result.status()).toBe(200);
  const body = await result.json();
  expect(body.items).toHaveLength(5);
  expect(body.total).toBe(12);
  expect(body.page).toBe(1);
  expect(body.size).toBe(5);
  expect(Array.isArray(body.departments)).toBe(true);
  expect((await hr.c.get("/api/v1/directory?size=101")).status()).toBe(400);
  expect((await hr.c.get("/api/v1/directory?direction=invalid")).status()).toBe(
    400,
  );
  const scoped = await (await self.c.get("/api/v1/directory")).json();
  const summary = await (await self.c.get("/api/v1/people-summary")).json();
  expect(summary.total).toBe(1);
  expect((await self.c.get("/api/v1/employees/E006")).status()).toBe(403);
  expect((await self.c.get("/api/v1/employees/E900")).status()).toBe(403);
  expect((await (await self.c.get("/api/v1/employees/E003")).json()).id).toBe(
    "E003",
  );
  expect(scoped.total).toBe(1);
  expect(scoped.items.map((e: { id: string }) => e.id)).toEqual(["E003"]);
  await Promise.all([hr.c.dispose(), self.c.dispose()]);
});
