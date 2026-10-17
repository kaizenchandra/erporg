"use client";
import { useState } from "react";
import { Icon } from "@/components/ui/icon";
import { signIn } from "@/lib/api";
export function Login({
  authMode,
  refresh,
  onError,
}: {
  authMode: string;
  refresh: () => Promise<void>;
  onError: (s: string) => void;
}) {
  const [username, setUsername] = useState("amelia"),
    [password, setPassword] = useState("atlas-demo"),
    [loginBusy, setLoginBusy] = useState(false);
  return (
    <section className="login-card">
      <div className="login-illustration">
        <Icon name="org" size={56} />
        <h2>
          Good work starts
          <br />
          with your people.
        </h2>
        <p>
          A calmer, more connected workspace for every step of the employee
          journey.
        </p>
        <span>ATLAS · PEOPLE & OPERATIONS</span>
      </div>
      <form
        onSubmit={async (e) => {
          e.preventDefault();
          setLoginBusy(true);
          onError("");
          try {
            await signIn(username, password);
            await refresh();
          } catch (err) {
            onError(err instanceof Error ? err.message : "Sign-in failed.");
          } finally {
            setLoginBusy(false);
          }
        }}
      >
        <div className="eyebrow">WELCOME TO ATLAS</div>
        <h2>Make yourself at home.</h2>
        <p>
          {authMode === "local"
            ? "Sign in to the synthetic local workspace."
            : "Use your organisation’s secure sign-in."}
        </p>
        {authMode === "local" ? (
          <>
            <label>
              Workspace account
              <select
                value={username}
                onChange={(e) => setUsername(e.target.value)}
              >
                <option value="amelia">
                  Amelia Bennett · HR administrator
                </option>
                <option value="olivia">Olivia Taylor · HR reviewer</option>
                <option value="marcus">Marcus Chen · Team manager</option>
                <option value="sophia">Sophia Martinez · Employee</option>
                <option value="returning">
                  Jordan Lee · Returning employee
                </option>
              </select>
            </label>
            <label>
              Demo password
              <input
                type="password"
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
              />
            </label>
            <button className="button primary" disabled={loginBusy}>
              {loginBusy ? "Signing in…" : "Enter workspace"}
              <Icon name="arrow" size={17} />
            </button>
            <small>Local demonstration only · No real employee data</small>
          </>
        ) : (
          <a className="button primary" href="/oauth2/authorization/keycloak">
            Sign in securely
            <Icon name="arrow" size={17} />
          </a>
        )}
      </form>
    </section>
  );
}
