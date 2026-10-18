"use client";
import { RehireAccessPanel } from "./rehire-access";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { formatDate } from "@/components/ui/primitives";
export type Activation = {
  id: string;
  rehireId: string;
  employeeId: string;
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
export type Completion = {
  activationId: string;
  rehireId: string;
  employeeId: string;
  subject: string;
  role: "EMPLOYEE";
  effectiveDate: string;
  inputVersion: number;
  newVersion: number;
  actor: string;
  reason: string;
  recordedAt: string;
  correlationId: string;
};
export type ActivationContext = {
  rehireId: string;
  employeeId: string;
  startDate: string;
  employmentStatus: string;
  employeeVersion: number;
  identities: string[];
  completion: Completion | null;
  history: Activation[];
};
export function RehireActivationPanel({
  employeeId,
  actor,
  onDirty,
  onBusy,
  onSaved,
}: {
  employeeId: string;
  actor: Actor;
  onDirty: (v: boolean) => void;
  onBusy: (v: boolean) => void;
  onSaved: () => Promise<void>;
}) {
  const [data, setData] = useState<ActivationContext | null>(null),
    [revision, setRevision] = useState(0),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [mode, setMode] = useState<"request" | "review" | null>(null),
    [subject, setSubject] = useState(""),
    [reason, setReason] = useState(""),
    [decision, setDecision] = useState("APPROVED"),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null),
    heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const c = new AbortController();
    api<ActivationContext>(
      `/api/v1/employees/${employeeId}/rehire/activation`,
      { signal: c.signal },
    )
      .then((r) => {
        if (!c.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setError(
            e instanceof Error
              ? e.message
              : "Unable to load completion context.",
          );
      });
    return () => c.abort();
  }, [employeeId, revision]);
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
    own = actor.employeeId === employeeId,
    started = !!data && data.startDate <= new Date().toISOString().slice(0, 10);
  const canReview =
    pending &&
    !own &&
    pending.actor !== actor.subject &&
    pending.actorEmployeeId !== actor.employeeId;
  const canApprove =
    started &&
    pending?.inputVersion === data?.employeeVersion &&
    data?.employmentStatus === "Onboarding";
  function open(value: "request" | "review") {
    setMode(value);
    setError("");
    setFields({});
    key.current = crypto.randomUUID();
    setDecision(value === "review" && !canApprove ? "REJECTED" : "APPROVED");
    onDirty(true);
    requestAnimationFrame(() =>
      document
        .getElementById(
          value === "request" ? "activation-subject" : "activation-reason",
        )
        ?.focus(),
    );
  }
  return (
    <section
      className="change-form"
      aria-label="Rehire completion and fresh access"
    >
      <h4 ref={heading} tabIndex={-1}>
        Complete onboarding and approve fresh access
      </h4>
      <p>
        HR attests completion and nominates one existing identity. A different
        HR employee must approve. The grant is Employee only, regardless of old
        roles. A fresh sign-in is required; old sessions and other aliases
        remain blocked. External account enablement and payroll are separate
        pending work.
      </p>
      {notice && <p role="status">{notice}</p>}
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to save completion/access review</strong>
          <p>{error}</p>
          {Object.entries(fields).map(([f, message]) => (
            <p key={f}>
              <a
                href={`#activation-${f}`}
                onClick={() =>
                  document.getElementById(`activation-${f}`)?.focus()
                }
              >
                {message}
              </a>
            </p>
          ))}
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={() => {
              setError("");
              setRevision(revision + 1);
            }}
          >
            Refresh completion context
          </button>
        </div>
      )}
      {!data ? (
        <p role="status">Loading completion and access…</p>
      ) : (
        <>
          {data.history.map((r) => (
            <article className="change-form" key={r.id}>
              <strong>
                {r.status} · Employee-only identity {r.subject}
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
          {data.completion ? (
            <>
              <p>
                <strong>
                  Active from {formatDate(data.completion.effectiveDate)}
                </strong>{" "}
                · Original local Employee grant for{" "}
                <strong>{data.completion.subject}</strong>.
              </p>
              <p>
                Completed by {data.completion.actor} ·{" "}
                {new Date(data.completion.recordedAt).toLocaleString()} ·{" "}
                {data.completion.reason}
              </p>
              <RehireAccessPanel
                actor={actor}
                employeeId={employeeId}
                onDirty={onDirty}
                onBusy={onBusy}
                onSaved={onSaved}
              />
              <p>
                The original grant did not restore HR/manager privilege or any
                other alias. Explicit replacements and revocations are shown in
                Current local access. External provider and payroll work remain
                separate.
              </p>
            </>
          ) : pending ? (
            <p>
              {canReview
                ? "Independent completion and access review required."
                : "Another HR employee must review this request."}{" "}
              Access remains unchanged while pending.
            </p>
          ) : !started ? (
            <p>
              The approved start date {formatDate(data.startDate)} has not been
              reached. Completion and access cannot be approved early.
            </p>
          ) : !data.identities.length ? (
            <p>
              No identity is mapped to this employee. Identity operations must
              configure and verify a provider mapping before a request can be
              submitted.
            </p>
          ) : null}
          {!mode ? (
            <>
              {canReview && (
                <button
                  className="button secondary"
                  onClick={() => open("review")}
                >
                  Review onboarding and access
                </button>
              )}
              {!pending &&
                !data.completion &&
                !own &&
                started &&
                data.employmentStatus === "Onboarding" &&
                data.identities.length > 0 &&
                data.history.length < 20 && (
                  <button
                    className="button secondary"
                    onClick={() => open("request")}
                  >
                    Request onboarding completion and access
                  </button>
                )}
            </>
          ) : (
            <form
              noValidate
              onSubmit={async (e) => {
                e.preventDefault();
                if (busy) return;
                setBusy(true);
                onBusy(true);
                setError("");
                setFields({});
                try {
                  const base = `/api/v1/employees/${employeeId}/rehire/activation`;
                  await api(
                    mode === "request" ? base : `${base}/${pending!.id}/review`,
                    {
                      method: "POST",
                      body: JSON.stringify({
                        reason,
                        version: data.employeeVersion,
                        idempotencyKey: key.current,
                        ...(mode === "request" ? { subject } : { decision }),
                      }),
                    },
                  );
                  setMode(null);
                  setSubject("");
                  setReason("");
                  onDirty(false);
                  setNotice(
                    "Completion/access decision recorded. Review current status; nominated access requires a fresh sign-in.",
                  );
                  setRevision(revision + 1);
                  await onSaved().catch(() =>
                    setNotice(
                      "Decision recorded. Profile refresh failed; close and reopen to load the current status.",
                    ),
                  );
                  heading.current?.focus();
                } catch (e) {
                  setError(
                    `${e instanceof Error ? e.message : "Save failed."} Entered values are preserved. Refresh context before retrying after a conflict.`,
                  );
                  if (e instanceof ApiError) setFields(e.fieldErrors);
                } finally {
                  setBusy(false);
                  onBusy(false);
                }
              }}
            >
              {mode === "request" ? (
                <>
                  <label htmlFor="activation-subject">
                    Identity for fresh Employee access (required)
                  </label>
                  <select
                    id="activation-subject"
                    required
                    value={subject}
                    aria-invalid={!!fields.subject}
                    aria-describedby={
                      fields.subject ? "activation-subject-error" : undefined
                    }
                    onChange={(e) => setSubject(e.target.value)}
                  >
                    <option value="">Choose one mapped identity</option>
                    {data.identities.map((s) => (
                      <option key={s} value={s}>
                        {s}
                      </option>
                    ))}
                  </select>
                  {fields.subject && (
                    <p className="field-error" id="activation-subject-error">
                      {fields.subject}
                    </p>
                  )}
                </>
              ) : (
                <label>
                  Completion/access decision (required)
                  <select
                    value={decision}
                    onChange={(e) => setDecision(e.target.value)}
                  >
                    <option value="APPROVED" disabled={!canApprove}>
                      Approve completion and Employee access
                    </option>
                    <option value="REJECTED">Reject request</option>
                  </select>
                </label>
              )}
              <label htmlFor="activation-reason">
                Completion/access reason (required)
              </label>
              <textarea
                id="activation-reason"
                required
                maxLength={500}
                value={reason}
                aria-invalid={!!fields.reason}
                aria-describedby={
                  fields.reason ? "activation-reason-error" : undefined
                }
                onChange={(e) => setReason(e.target.value)}
              />
              {fields.reason && (
                <p className="field-error" id="activation-reason-error">
                  {fields.reason}
                </p>
              )}
              <p>
                {mode === "request"
                  ? "Attest that the required onboarding evidence is complete and submit one identity for independent least-privilege review. Nothing is activated on submission."
                  : decision === "APPROVED"
                    ? `Approve employee ${employeeId} as Active effective today (UTC) and grant ${pending!.subject} fresh Employee-only local access. Old roles and sessions are not reused. This is a manual attestation, not automatic task execution or provider provisioning.`
                    : "Reject the request without completing onboarding or granting access."}
              </p>
              <div className="form-footer">
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() => {
                    if (
                      (!subject && !reason) ||
                      window.confirm(
                        "Discard unsaved completion/access values?",
                      )
                    ) {
                      setMode(null);
                      setSubject("");
                      setReason("");
                      onDirty(false);
                    }
                  }}
                >
                  Cancel completion form
                </button>
                <button className="button primary" disabled={busy}>
                  {busy
                    ? "Saving…"
                    : mode === "request"
                      ? "Submit completion/access request"
                      : "Record completion/access decision"}
                </button>
              </div>
            </form>
          )}
        </>
      )}
    </section>
  );
}
