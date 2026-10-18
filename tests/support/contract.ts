import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import { expect, type APIResponse } from "@playwright/test";
import contract from "../../docs/openapi.json";

type Schema = Record<string, unknown>;
type Operation = {
  responses: Record<string, { content?: Record<string, { schema: Schema }> }>;
};
const paths = contract.paths as Record<string, Record<string, Operation>>;
const ajv = new Ajv2020({ allErrors: true, strict: true });
addFormats(ajv);
export function validator(schema: Schema) {
  const converted = JSON.parse(
    JSON.stringify({
      ...schema,
      $defs: contract.components.schemas,
    }).replaceAll("#/components/schemas/", "#/$defs/"),
  );
  return ajv.compile(converted);
}
export function schemaValidator(
  name: keyof typeof contract.components.schemas,
) {
  return validator({ $ref: `#/$defs/${name}` });
}
export async function checkResponse(
  response: Pick<APIResponse, "status" | "headers" | "json">,
  path: string,
  method = "get",
) {
  const operation = paths[path]?.[method];
  expect(operation, `Missing operation ${method} ${path}`).toBeDefined();
  const documented = operation.responses[String(response.status())];
  expect(
    documented,
    `Undocumented status ${response.status()} for ${path}`,
  ).toBeDefined();
  const schema = documented.content?.["application/json"]?.schema;
  expect(
    schema,
    `No JSON response schema for ${path} (${response.status()})`,
  ).toBeDefined();
  expect(response.headers()["content-type"]).toContain("application/json");
  const body: unknown = await response.json();
  const validate = validator(schema!);
  // Diagnostics include schema paths/keywords, never employee field values.
  expect(
    validate(body),
    JSON.stringify(
      validate.errors?.map((e) => ({
        path: e.instancePath,
        keyword: e.keyword,
      })),
    ),
  ).toBe(true);
  return body;
}
