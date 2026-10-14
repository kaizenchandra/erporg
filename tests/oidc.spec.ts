import { test, expect } from "@playwright/test";
import { createHmac } from "node:crypto";
function syntheticOtp() {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  const bits = [..."JBSWY3DPEHPK3PXP"]
    .map((c) => alphabet.indexOf(c).toString(2).padStart(5, "0"))
    .join("");
  const secret = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 30000)));
  const hash = createHmac("sha1", secret).update(counter).digest();
  const offset = hash[19] & 15;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1000000)
    .toString()
    .padStart(6, "0");
}
test("OIDC code flow requires MFA for the privileged synthetic account", async ({
  page,
}) => {
  test.skip(
    process.env.OIDC_TEST !== "1",
    "Run explicitly with oidc-demo backend and Keycloak.",
  );
  await page.goto("/");
  await page.getByRole("link", { name: "Sign in securely" }).click();
  await page.locator("#username").fill("amelia");
  await page.locator("#password").fill("atlas-demo");
  await page.locator("#kc-login").click();
  await expect(page.locator("#otp")).toBeVisible();
  await page.locator("#otp").fill(syntheticOtp());
  await page.locator("#kc-login").click();
  await page.waitForURL("http://127.0.0.1:3000/");
  await expect(page.getByRole("table")).toBeVisible();
  const session = await (await page.request.get("/api/v1/session")).json();
  expect(session.subject).toBe("00000000-0000-0000-0000-000000000001");
  expect(session.role).toBe("HR");
  await page.screenshot({ path: "docs/evidence/oidc-mfa.png", fullPage: true });
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(
    page.getByRole("link", { name: "Sign in securely" }),
  ).toBeVisible();
  expect((await page.request.get("/api/v1/session")).status()).toBe(401);
});
