"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Revocation } from "./rehire-access-contracts";
import type { Actor } from "@/lib/contracts";
type Replacement = {
  id: string;
  employeeId: string;
  predecessorId: string;
  subject: string;
  reason: string;
  inputVersion: number;
  actor: string;
  actorEmployeeId: string;
  recordedAt: string;
  correlationId: string;
  status: "SUBMITTED" | "APPROVED" | "REJECTED";
  reviewer: string | null;
  reviewerEmployeeId: string | null;
  comment: string | null;
  reviewVersion: number | null;
  reviewedAt: string | null;
  reviewCorrelationId: string | null;
};
type Context = {
  employeeId: string;
  employeeVersion: number;
  employmentStatus: string;
  grant: {
    activationId: string;
    employeeId: string;
    subject: string;
    role: "EMPLOYEE";
    grantNumber: number;
    recordedAt: string;
  };
  accessStatus: "GRANTED" | "REVOKED";
  grants: Context["grant"][];
  revocations: Revocation[];
  candidates: string[];
  history: Replacement[];
  canRequest: boolean;
};
export function IdentityReplacementPanel({
  employeeId,
  employeeVersion,
  actor,
  onDirty,
  onBusy,
  onSaved,
}: {
  employeeId: string;
  employeeVersion: number;
  actor: Actor;
  onDirty: (v: boolean) => void;
  onBusy: (v: boolean) => void;
  onSaved: () => Promise<void>;
}) {
  const [data, setData] = useState<Context | null>(null),
    [revision, setRevision] = useState(0),
    [mode, setMode] = useState<"request" | "review" | null>(null),
    [subject, setSubject] = useState(""),
    [reason, setReason] = useState(""),
    [decision, setDecision] = useState("APPROVED"),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false);
  const summary = useRef<HTMLDivElement>(null),
    heading = useRef<HTMLHeadingElement>(null),
    key = useRef(crypto.randomUUID()),
    inFlight = useRef(false),
    reviewId = useRef<string | null>(null);
  useEffect(() => {
    const c = new AbortController();
    api<Context>(`/api/v1/employees/${employeeId}/rehire/access/replacement`, {
      signal: c.signal,
    })
      .then((r) => {
        if (!c.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setError(
            e instanceof Error
              ? e.message
              : "Unable to load replacement context.",
          );
      });
    return () => c.abort();
  }, [employeeId, employeeVersion, revision]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (mode || busy) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [mode, busy]);
  const pending = data?.history.find((r) => r.status === "SUBMITTED"),
    canReview =
      !!pending &&
      pending.actor !== actor.subject &&
      pending.actorEmployeeId !== actor.employeeId &&
      employeeId !== actor.employeeId;
  const canApprove =
    !!data &&
    !!pending &&
    data.accessStatus === "REVOKED" &&
    data.grant.grantNumber === 1 &&
    data.employmentStatus === "Active" &&
    pending.inputVersion === data.employeeVersion &&
    data.candidates.includes(pending.subject);
  function open(next: "request" | "review") {
    reviewId.current = next === "review" ? pending!.id : null;
    setMode(next);
    setError("");
    setReason("");
    setSubject("");
    setDecision(next === "review" && !canApprove ? "REJECTED" : "APPROVED");
    key.current = crypto.randomUUID();
    onDirty(true);
    requestAnimationFrame(() =>
      document
        .getElementById(
          next === "request" ? "replacement-subject" : "replacement-reason",
        )
        ?.focus(),
    );
  }
  return (
    <section
      className="change-form rehire-access-panel"
      aria-label="Replacement identity review"
    >
      <h4 ref={heading} tabIndex={-1}>
        Replace a revoked identity
      </h4>
      <p>
        Nominate a different existing mapped identity. A different HR employee
        must review. Approval grants Employee-only local access and requires
        fresh sign-in. The old identity stays revoked, including after a
        membership move. No provider account is created or enabled.
      </p>
      {notice && <p role="status">{notice}</p>}
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to complete identity replacement</strong>
          <p>{error}</p>
          {mode && (
            <p>
              <a
                href={
                  mode === "request" && !subject
                    ? "#replacement-subject"
                    : "#replacement-reason"
                }
                onClick={() =>
                  document
                    .getElementById(
                      mode === "request" && !subject
                        ? "replacement-subject"
                        : "replacement-reason",
                    )
                    ?.focus()
                }
              >
                Review the required replacement fields
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
                const current = await api<Context>(
                  `/api/v1/employees/${employeeId}/rehire/access/replacement`,
                );
                const live = current.history.find(
                  (r) => r.status === "SUBMITTED",
                );
                if (
                  (mode === "review" && live?.id !== reviewId.current) ||
                  (mode === "request" &&
                    (live || current.grant.grantNumber === 2))
                ) {
                  setMode(null);
                  setReason("");
                  setSubject("");
                  onDirty(false);
                  setNotice(
                    "Current status has changed. Review the recorded history before another command.",
                  );
                }
                setData(current);
                setError("");
              } catch (e: unknown) {
                setError(
                  e instanceof Error
                    ? e.message
                    : "Refresh failed. Keep the entered values and retry.",
                );
              } finally {
                setBusy(false);
              }
            }}
          >
            Refresh replacement context
          </button>
        </div>
      )}
      {!data ? (
        <p role="status">Loading replacement history…</p>
      ) : (
        <>
          <details>
            <summary>Access grants and revocations</summary>
            {data.grants.map((g) => (
              <article key={g.activationId}>
                <strong>
                  Grant {g.grantNumber} · {g.subject} · {g.role}
                </strong>
                <p>Recorded {new Date(g.recordedAt).toLocaleString()}</p>
                <p>Grant reference: {g.activationId}</p>
              </article>
            ))}
            {data.revocations.map((r) => (
              <article key={r.activationId}>
                <strong>Revoked identity · {r.subject}</strong>
                <p>{r.reason}</p>
                <p>
                  By {r.actor} · {new Date(r.recordedAt).toLocaleString()}
                </p>
                <p>Correlation: {r.correlationId}</p>
              </article>
            ))}
          </details>
          {data.history.length === 0 && (
            <p>No replacement requests recorded.</p>
          )}
          {data.history.map((r) => (
            <article key={r.id}>
              <strong>
                {r.status} · Replacement identity {r.subject}
              </strong>
              <p>
                Requested by {r.actor} ·{" "}
                {new Date(r.recordedAt).toLocaleString()}
              </p>
              <p>{r.reason}</p>
              {r.reviewer && (
                <>
                  <p>
                    Reviewed by {r.reviewer} ·{" "}
                    {new Date(r.reviewedAt!).toLocaleString()}
                  </p>
                  <p>{r.comment}</p>
                </>
              )}
            </article>
          ))}
          {!mode && pending && (
            <>
              {canReview ? (
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() => open("review")}
                >
                  Review replacement identity
                </button>
              ) : (
                <p>
                  Another HR employee must review this replacement. Access stays
                  revoked until approval.
                </p>
              )}
            </>
          )}
          {!mode &&
            data.canRequest &&
            !pending &&
            (data.candidates.length ? (
              <button
                type="button"
                className="button secondary"
                disabled={busy}
                onClick={() => open("request")}
              >
                Request replacement identity
              </button>
            ) : (
              <p>
                No unused mapped identity is available. Ask authorised identity
                operations to configure a different identity; this application
                does not create provider accounts.
              </p>
            ))}
          {!mode && !data.canRequest && !pending && (
            <p>
              {data.grant.grantNumber === 2
                ? "One replacement has been used. The current grant can still be revoked; further replacement requires a later controlled workflow."
                : "Revoke the current local grant before requesting a replacement. Employment must remain Active."}
            </p>
          )}
          {mode && (
            <form
              noValidate
              onSubmit={async (e) => {
                e.preventDefault();
                if (inFlight.current) return;
                if (
                  !reason.trim() ||
                  reason.length > 500 ||
                  (mode === "request" && !subject)
                ) {
                  setError(
                    "Choose the required identity and enter a reason of 1–500 characters. No command was submitted.",
                  );
                  requestAnimationFrame(() => summary.current?.focus());
                  return;
                }
                inFlight.current = true;
                setBusy(true);
                onBusy(true);
                setError("");
                let saved = false;
                try {
                  const url =
                    `/api/v1/employees/${employeeId}/rehire/access/replacement` +
                    (mode === "review" ? `/${reviewId.current}/review` : "");
                  await api<Replacement>(url, {
                    method: "POST",
                    body: JSON.stringify({
                      ...(mode === "request" ? { subject } : { decision }),
                      reason,
                      version: data.employeeVersion,
                      idempotencyKey: key.current,
                    }),
                  });
                  saved = true;
                  setMode(null);
                  setReason("");
                  setSubject("");
                  onDirty(false);
                  setNotice(
                    "Replacement command recorded. Review the current grant; the old identity remains held.",
                  );
                  const refreshed = await api<Context>(
                    `/api/v1/employees/${employeeId}/rehire/access/replacement`,
                  );
                  setData(refreshed);
                  await onSaved();
                  requestAnimationFrame(() => heading.current?.focus());
                } catch (e: unknown) {
                  if (saved) {
                    setNotice(
                      "Replacement command was recorded. Refresh current access before another command; do not repeat the confirmed write.",
                    );
                    setRevision((v) => v + 1);
                  } else
                    setError(
                      e instanceof Error
                        ? e.message
                        : "Outcome could not be confirmed. Refresh replacement context before retrying.",
                    );
                } finally {
                  inFlight.current = false;
                  setBusy(false);
                  onBusy(false);
                }
              }}
            >
              {mode === "request" ? (
                <>
                  <label htmlFor="replacement-subject">
                    Replacement identity (required)
                  </label>
                  <select
                    id="replacement-subject"
                    value={subject}
                    disabled={busy}
                    required
                    onChange={(e) => setSubject(e.target.value)}
                  >
                    <option value="">Choose an unused identity</option>
                    {data.candidates.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                  <p>
                    Showing up to twenty unused mapped identities in subject
                    order. The held identity cannot be selected.
                  </p>
                </>
              ) : (
                <>
                  <p>
                    Review replacement of {data.grant.subject} with{" "}
                    {pending!.subject}. Approval gives {pending!.subject}{" "}
                    Employee-only local access; {data.grant.subject} stays held.
                    Fresh sign-in is required. No payroll or provider change is
                    performed.
                  </p>
                  <label htmlFor="replacement-decision">
                    Replacement decision
                  </label>
                  <select
                    id="replacement-decision"
                    value={decision}
                    disabled={busy}
                    onChange={(e) => setDecision(e.target.value)}
                  >
                    <option value="APPROVED" disabled={!canApprove}>
                      Approve replacement
                    </option>
                    <option value="REJECTED">Reject replacement</option>
                  </select>
                  {!canApprove && (
                    <p>
                      The snapshot or identity is no longer eligible. Reject
                      this request and arrange a fresh review.
                    </p>
                  )}
                </>
              )}
              <label htmlFor="replacement-reason">
                Replacement reason (required)
              </label>
              <textarea
                id="replacement-reason"
                value={reason}
                maxLength={500}
                required
                disabled={busy}
                onChange={(e) => setReason(e.target.value)}
              />
              {mode === "request" && (
                <p>
                  This submits a request only. The existing identity stays
                  revoked until independent approval of the new Employee-only
                  grant.
                </p>
              )}
              <div className="form-footer">
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() => {
                    setMode(null);
                    setReason("");
                    setSubject("");
                    setError("");
                    onDirty(false);
                    requestAnimationFrame(() => heading.current?.focus());
                  }}
                >
                  Cancel replacement form
                </button>
                <button className="button primary" disabled={busy}>
                  {busy
                    ? "Recording…"
                    : mode === "request"
                      ? "Submit replacement request"
                      : "Record replacement decision"}
                </button>
              </div>
            </form>
          )}
        </>
      )}
    </section>
  );
}
