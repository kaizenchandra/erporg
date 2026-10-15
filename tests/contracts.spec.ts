import {
  test,
  expect,
  request,
  type APIRequestContext,
} from "@playwright/test";
import { checkResponse, schemaValidator } from "./support/contract";
import type { ChangeRequest, Employee } from "../lib/contracts";

async function login(user: string) {
  const client = await request.newContext({ baseURL: "http://127.0.0.1:3000" });
  const csrf = await (await client.get("/api/csrf")).json();
  expect(
    (
      await client.post("/api/login", {
        form: { username: user, password: "atlas-demo" },
        headers: { [csrf.headerName]: csrf.token },
      })
    ).status(),
  ).toBe(204);
  const token = await (await client.get("/api/csrf")).json();
  return { client, headers: { [token.headerName]: token.token } };
}

test("live read and command responses conform to OpenAPI JSON schemas", async () => {
  const contexts: APIRequestContext[] = [];
  try {
    const hr = await login("amelia"),
      self = await login("sophia");
    contexts.push(hr.client, self.client);
    for (const [path, template] of [
      ["/api/csrf", "/api/csrf"],
      ["/api/auth-mode", "/api/auth-mode"],
      ["/api/v1/session", "/api/v1/session"],
      ["/api/v1/employees", "/api/v1/employees"],
      ["/api/v1/requests", "/api/v1/requests"],
      ["/api/v1/directory?size=5", "/api/v1/directory"],
      ["/api/v1/request-inbox?size=5", "/api/v1/request-inbox"],
      ["/api/v1/people-summary", "/api/v1/people-summary"],
      ["/api/v1/employees/E003", "/api/v1/employees/{id}"],
      ["/api/v1/employees/E003/history", "/api/v1/employees/{id}/history"],
      ["/api/v1/notifications", "/api/v1/notifications"],
    ]) {
      const response = await hr.client.get(path);
      expect(response.status()).toBe(200);
      await checkResponse(response, template);
    }
    const employee: Employee = await (
      await self.client.get("/api/v1/employees/E003")
    ).json();
    const submitted = await self.client.post("/api/v1/requests", {
      headers: self.headers,
      data: {
        employeeId: employee.id,
        field: "phone",
        newValue: "+44 7700 " + Date.now().toString().slice(-6),
        effectiveDate: new Date().toISOString().slice(0, 10),
        reason: "Synthetic contract validation",
        version: employee.version,
        idempotencyKey: crypto.randomUUID(),
      },
    });
    expect(submitted.status()).toBe(200);
    const change = (await checkResponse(
      submitted,
      "/api/v1/requests",
      "post",
    )) as ChangeRequest;
    const decision = await hr.client.post(
      `/api/v1/requests/${change.id}/decision`,
      {
        headers: hr.headers,
        data: { decision: "REJECTED", comment: "Contract test complete" },
      },
    );
    expect(decision.status()).toBe(200);
    await checkResponse(decision, "/api/v1/requests/{id}/decision", "post");
    const invalid = await self.client.post("/api/v1/requests", {
      headers: self.headers,
      data: {
        employeeId: employee.id,
        field: "phone",
        newValue: "invalid",
        reason: "",
        effectiveDate: null,
        version: employee.version,
      },
    });
    expect(invalid.status()).toBe(400);
    await checkResponse(invalid, "/api/v1/requests", "post");
    const conflict = await hr.client.post(
      `/api/v1/requests/${change.id}/decision`,
      {
        headers: hr.headers,
        data: { decision: "REJECTED", comment: "Repeated decision" },
      },
    );
    expect(conflict.status()).toBe(409);
    await checkResponse(conflict, "/api/v1/requests/{id}/decision", "post");
    const history = (await checkResponse(
      await self.client.get("/api/v1/employees/E003/history"),
      "/api/v1/employees/{id}/history",
    )) as unknown[];
    expect(history.length).toBeGreaterThan(0);
    const notifications = (await checkResponse(
      await self.client.get("/api/v1/notifications"),
      "/api/v1/notifications",
    )) as unknown[];
    expect(notifications.length).toBeGreaterThan(0);
  } finally {
    await Promise.all(contexts.map((c) => c.dispose()));
  }
});

test("contract validator rejects incorrect types, formats and unexpected private fields", () => {
  const validate = schemaValidator("Employee");
  const employee = {
    id: "E1",
    name: "Synthetic",
    email: "synthetic@example.test",
    phone: "12345678",
    address: "Synthetic address",
    emergencyContactName: "Synthetic contact",
    emergencyContactRelationship: "Sibling",
    emergencyContactPhone: "12345678",
    title: "Engineer",
    department: "Test",
    location: "Test",
    status: "Active",
    managerId: null,
    startDate: "2026-10-14",
    positionCode: "",
    version: 0,
  };
  expect(validate(employee)).toBe(true);
  for (const altered of [
    { ...employee, version: "0" },
    { ...employee, startDate: "2026-02-30" },
    { ...employee, bankAccount: "must-not-leak" },
  ])
    expect(validate(altered)).toBe(false);
  expect(
    schemaValidator("DirectoryPage")({
      items: [],
      total: 1,
      page: 0,
      size: 101,
      departments: [],
    }),
  ).toBe(false);
});
