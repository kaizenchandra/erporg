"use client";
import { useEffect, useRef, useState } from "react";
import { IdentityReplacementPanel } from "./identity-replacement";
import type { Actor } from "@/lib/contracts";
import { api, ApiError } from "@/lib/api";
import type { Revocation } from "./rehire-access-contracts";
type AccessContext = {
  employeeId: string;
  employeeVersion: number;
  employmentStatus: string;
  activationId: string;
  subject: string;
  role: "EMPLOYEE";
  accessStatus: "GRANTED" | "REVOKED";
  grantNumber: number;
  revocation: Revocation | null;
};
export function RehireAccessPanel({
  employeeId,
  actor,
  onDirty,
  onBusy,
  onSaved,
}: {
  employeeId: string;
  actor: Actor;
  onDirty: (value: boolean) => void;
  onBusy: (value: boolean) => void;
  onSaved: () => Promise<void>;
}) {
  const [data, setData] = useState<AccessContext | null>(null),
    [error, setError] = useState(""),
    [reason, setReason] = useState(""),
    [editing, setEditing] = useState(false),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const summary = useRef<HTMLDivElement>(null),
    reasonInput = useRef<HTMLTextAreaElement>(null),
    button = useRef<HTMLButtonElement>(null),
    key = useRef(crypto.randomUUID()),
    inFlight = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    api<AccessContext>(`/api/v1/employees/${employeeId}/rehire/access`, {
      signal: controller.signal,
    })
      .then((r) => {
        if (!controller.signal.aborted) {
          setData(r);
        }
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error
              ? e.message
              : "Unable to load current access. Retry.",
          );
      });
    return () => controller.abort();
  }, [employeeId]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (editing || busy) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [editing, busy]);
  return (
    <section
      className="change-form rehire-access-panel"
      aria-label="Current rehire local access"
    >
      <h4>Current local access</h4>
      {notice && <p role="status">{notice}</p>}
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to revoke local access</strong>
          <p>{error}</p>
          {editing && (
            <p>
              <a
                href="#revoke-access-reason"
                onClick={() => reasonInput.current?.focus()}
              >
                Review the required revocation reason
              </a>
            </p>
          )}
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={async () => {
              setBusy(true);
              try {
                const current = await api<AccessContext>(
                  `/api/v1/employees/${employeeId}/rehire/access`,
                );
                setData(current);
                setError("");
                if (current.revocation) {
                  setEditing(false);
                  setReason("");
                  onDirty(false);
                  setNotice(
                    "Confirmed: local access is revoked. No repeat command is needed.",
                  );
                }
              } catch (e: unknown) {
                setError(
                  e instanceof Error
                    ? e.message
                    : "Refresh failed. Keep the entered reason and retry.",
                );
              } finally {
                setBusy(false);
              }
            }}
          >
            Refresh access context
          </button>
        </div>
      )}
      {!data ? (
        <p role="status">Loading current local grant…</p>
      ) : (
        <>
          <p>
            <strong>{data.accessStatus}</strong> · {data.subject} · Employee
            only
          </p>
          {data.revocation ? (
            <>
              <p>
                Local access revoked by {data.revocation.actor} ·{" "}
                {new Date(data.revocation.recordedAt).toLocaleString()}
              </p>
              <p>{data.revocation.reason}</p>
              <p>
                Existing sessions and new local sign-ins are blocked. Employment
                remains {data.employmentStatus}; onboarding completion and the
                original grant are retained. External provider disablement
                remains separate. A different identity requires the independent
                replacement review below.
              </p>
            </>
          ) : (
            <>
              <p>
                Revoke this identity’s local access immediately. Existing
                sessions fail on their next request and new sign-ins are denied.
                This does not end employment, undo onboarding, change payroll or
                disable the external provider. Other aliases remain blocked.
                There is no undo; any different identity requires independent
                replacement review after revocation.
              </p>
              {!editing ? (
                <button
                  ref={button}
                  type="button"
                  className="button secondary"
                  onClick={() => {
                    setEditing(true);
                    setError("");
                    onDirty(true);
                    key.current = crypto.randomUUID();
                    requestAnimationFrame(() => reasonInput.current?.focus());
                  }}
                >
                  Revoke local access
                </button>
              ) : (
                <form
                  noValidate
                  onSubmit={async (e) => {
                    e.preventDefault();
                    if (inFlight.current) return;
                    if (!reason.trim() || reason.length > 500) {
                      setError(
                        "Enter a revocation reason of 1–500 characters. Nothing has been revoked.",
                      );
                      requestAnimationFrame(() => summary.current?.focus());
                      return;
                    }
                    inFlight.current = true;
                    setBusy(true);
                    onBusy(true);
                    setError("");
                    try {
                      const r = await api<Revocation>(
                        `/api/v1/employees/${employeeId}/rehire/access/revoke`,
                        {
                          method: "POST",
                          body: JSON.stringify({
                            activationId: data.activationId,
                            reason,
                            version: data.employeeVersion,
                            idempotencyKey: key.current,
                          }),
                        },
                      );
                      setData({
                        ...data,
                        employeeVersion: r.newVersion,
                        accessStatus: "REVOKED",
                        revocation: r,
                      });
                      setReason("");
                      setEditing(false);
                      onDirty(false);
                      setNotice(
                        "Local access revoked. Existing sessions and new sign-ins are blocked.",
                      );
                      try {
                        await onSaved();
                      } catch {
                        setNotice(
                          "Local access was revoked. Refresh the profile to see the latest employee version; do not repeat the revocation.",
                        );
                      }
                    } catch (e: unknown) {
                      setError(
                        e instanceof ApiError || e instanceof Error
                          ? e.message
                          : "Revocation outcome could not be confirmed. Refresh access context before retrying.",
                      );
                    } finally {
                      inFlight.current = false;
                      setBusy(false);
                      onBusy(false);
                    }
                  }}
                >
                  <label htmlFor="revoke-access-reason">
                    Revocation reason (required)
                  </label>
                  <textarea
                    ref={reasonInput}
                    id="revoke-access-reason"
                    value={reason}
                    maxLength={500}
                    required
                    aria-invalid={!!error}
                    disabled={busy}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  <p>
                    Confirm immediate revocation of {data.subject} for employee{" "}
                    {data.employeeId}. Employment stays {data.employmentStatus}.
                    This security action is recorded immediately without a
                    second approval.
                  </p>
                  <div className="form-footer">
                    <button
                      type="button"
                      className="button secondary"
                      disabled={busy}
                      onClick={() => {
                        setEditing(false);
                        setReason("");
                        setError("");
                        onDirty(false);
                        requestAnimationFrame(() => button.current?.focus());
                      }}
                    >
                      Cancel revocation
                    </button>
                    <button className="button primary" disabled={busy}>
                      {busy ? "Revoking…" : "Confirm immediate revocation"}
                    </button>
                  </div>
                </form>
              )}
            </>
          )}
          <IdentityReplacementPanel
            employeeId={employeeId}
            employeeVersion={data.employeeVersion}
            actor={actor}
            onDirty={onDirty}
            onBusy={onBusy}
            onSaved={async () => {
              const current = await api<AccessContext>(
                `/api/v1/employees/${employeeId}/rehire/access`,
              );
              setData(current);
              await onSaved();
            }}
          />
        </>
      )}
    </section>
  );
}
