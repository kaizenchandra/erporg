"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { formatDate } from "@/components/ui/primitives";
import { RehireActivationPanel } from "./rehire-activation";
import { ManagerPicker } from "./manager-picker";
import { DepartmentPicker } from "./department-picker";
export type Rehire = {
  id: string;
  employeeId: string;
  startDate: string;
  title: string;
  department: string;
  location: string;
  managerId: string;
  priorInactiveDate: string;
  actor: string;
  actorEmployeeId: string;
  reason: string;
  inputVersion: number;
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
export type Episode = {
  number: number;
  startDate: string;
  inactiveDate: string | null;
  state: string;
  source: string;
  requestId: string | null;
  recordedAt: string | null;
};
export type RehireContext = {
  employeeId: string;
  employeeVersion: number;
  employmentStatus: string;
  priorInactiveDate: string | null;
  legacyAccessBlocked: boolean;
  episodes: Episode[];
  history: Rehire[];
};
const empty = {
  startDate: "",
  title: "",
  department: "",
  location: "",
  managerId: "",
  reason: "",
};
export function RehirePanel({
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
  const [data, setData] = useState<RehireContext | null>(null),
    [revision, setRevision] = useState(0),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [notice, setNotice] = useState(""),
    [mode, setMode] = useState<"request" | "review" | null>(null),
    [values, setValues] = useState(empty),
    [decision, setDecision] = useState("APPROVED"),
    [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null),
    heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const c = new AbortController();
    api<RehireContext>(`/api/v1/employees/${employeeId}/rehire`, {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((r) => {
        if (!c.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setError(
            e instanceof Error ? e.message : "Unable to load rehire context.",
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
    approved = data?.history.some((r) => r.status === "APPROVED"),
    own = actor.employeeId === employeeId;
  const canReview =
    pending &&
    !own &&
    pending.actor !== actor.subject &&
    pending.actorEmployeeId !== actor.employeeId;
  const canApprove =
    pending &&
    pending.startDate >= new Date().toISOString().slice(0, 10) &&
    pending.inputVersion === data?.employeeVersion &&
    data?.employmentStatus === "Ended";
  function open(value: "request" | "review") {
    key.current = crypto.randomUUID();
    setMode(value);
    onDirty(true);
    setError("");
    setFields({});
    setDecision(value === "review" && !canApprove ? "REJECTED" : "APPROVED");
    requestAnimationFrame(() =>
      document
        .getElementById(
          value === "request" ? "rehire-startDate" : "rehire-reason",
        )
        ?.focus(),
    );
  }
  const field = (name: keyof typeof empty, label: string, type = "text") => (
    <div>
      <label htmlFor={`rehire-${name}`}>{label} (required)</label>
      <input
        id={`rehire-${name}`}
        type={type}
        required
        value={values[name]}
        maxLength={type === "text" ? 120 : undefined}
        aria-invalid={!!fields[name]}
        aria-describedby={fields[name] ? `rehire-${name}-error` : undefined}
        onChange={(e) => setValues({ ...values, [name]: e.target.value })}
        onKeyDown={(e) => {
          if (type === "date" && e.key === "Escape") {
            e.preventDefault();
            e.stopPropagation();
            document.getElementById("rehire-reason")?.focus();
          }
        }}
      />
      {fields[name] && (
        <p className="field-error" id={`rehire-${name}-error`}>
          {fields[name]}
        </p>
      )}
    </div>
  );
  return (
    <section
      className="change-form"
      aria-label="Employment episodes and rehire"
    >
      <h3 ref={heading} tabIndex={-1}>
        Employment episodes and rehire
      </h3>
      <p>
        Keep the same employee identifier and personal history. A rehire creates
        a separate onboarding episode. Legacy accounts, privileged roles and
        sessions are not restored; fresh access review and onboarding completion
        remain separate.
      </p>
      {notice && <p role="status">{notice}</p>}
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to save rehire</strong>
          <p>{error}</p>
          {Object.entries(fields).map(([f, message]) => (
            <p key={f}>
              <a
                href={`#rehire-${f}`}
                onClick={() => document.getElementById(`rehire-${f}`)?.focus()}
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
            Refresh rehire context
          </button>
        </div>
      )}
      {!data ? (
        <p role="status">Loading employment episodes…</p>
      ) : (
        <>
          <ol>
            {data.episodes.map((e) => (
              <li key={e.number}>
                Episode {e.number} · {e.state} · {formatDate(e.startDate)}
                {e.inactiveDate &&
                  ` through ${formatDate(new Date(new Date(e.inactiveDate).getTime() - 86400000).toISOString().slice(0, 10))}`}{" "}
                ·{" "}
                {e.source === "REHIRE"
                  ? `Rehire recorded ${new Date(e.recordedAt!).toLocaleString()}`
                  : "Original employment; original recording time not available"}
              </li>
            ))}
          </ol>
          {data.history.map((r) => (
            <article key={r.id} className="history-event">
              <div>
                <strong>
                  {r.status} · Rehire start {formatDate(r.startDate)}
                </strong>
                <p>
                  {r.title} · {r.department} · {r.location} · Manager{" "}
                  {r.managerId}
                </p>
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
              </div>
            </article>
          ))}
          {approved && data.employmentStatus === "Active" ? (
            <p>
              Rehire onboarding was completed under a separate access approval.
              Prior roles and sessions were not restored; see the nominated
              fresh Employee grant below.
            </p>
          ) : approved ? (
            <p>
              Rehire approved. Before the start date the record remains Ended;
              from that date it shows Onboarding. All legacy identities remain
              locally blocked. Onboarding, payroll and identity-review handoffs
              are pending. No external account, role or payment was changed.
            </p>
          ) : pending ? (
            <p>
              {canReview
                ? "Independent HR review required."
                : "Another HR employee must review this rehire."}{" "}
              Employment and access remain unchanged until a decision.
            </p>
          ) : data.employmentStatus !== "Ended" ? (
            <p>
              Rehire is available only after the current employment end takes
              effect.
            </p>
          ) : own ? (
            <p>Another HR employee must request your rehire.</p>
          ) : null}
          {approved && (
            <RehireActivationPanel
              employeeId={employeeId}
              actor={actor}
              onDirty={onDirty}
              onBusy={onBusy}
              onSaved={async () => {
                setRevision(revision + 1);
                setNotice(
                  "Completion/access review recorded. Fresh sign-in is required for the nominated identity.",
                );
                await onSaved();
              }}
            />
          )}
          {!mode ? (
            <>
              {canReview && (
                <button
                  className="button secondary"
                  onClick={() => open("review")}
                >
                  Review rehire
                </button>
              )}
              {!pending &&
                !approved &&
                !own &&
                data.employmentStatus === "Ended" &&
                data.priorInactiveDate &&
                data.history.length < 20 && (
                  <button
                    className="button secondary"
                    onClick={() => open("request")}
                  >
                    Plan rehire
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
                  const base = `/api/v1/employees/${employeeId}/rehire`;
                  await api(
                    mode === "request" ? base : `${base}/${pending!.id}/review`,
                    {
                      method: "POST",
                      body: JSON.stringify(
                        mode === "request"
                          ? {
                              ...values,
                              startDate: values.startDate || null,
                              version: data.employeeVersion,
                              idempotencyKey: key.current,
                            }
                          : {
                              decision,
                              reason: values.reason,
                              version: data.employeeVersion,
                              idempotencyKey: key.current,
                            },
                      ),
                    },
                  );
                  setMode(null);
                  setValues(empty);
                  onDirty(false);
                  setNotice(
                    "Rehire decision/request recorded. Legacy access remains blocked; review the latest episode and pending steps.",
                  );
                  setRevision(revision + 1);
                  await onSaved().catch(() =>
                    setNotice(
                      "Rehire recorded. Profile refresh failed; close and reopen to load the current status. Legacy access remains blocked.",
                    ),
                  );
                  heading.current?.focus();
                } catch (e) {
                  setError(
                    `${e instanceof Error ? e.message : "Save failed."} Entered values are preserved. Refresh context and review the latest state before retrying after a conflict.`,
                  );
                  if (e instanceof ApiError) setFields(e.fieldErrors);
                } finally {
                  setBusy(false);
                  onBusy(false);
                }
              }}
            >
              {mode === "request" ? (
                <div className="form-grid">
                  {field("startDate", "Rehire start date", "date")}
                  {field("title", "Rehire job title")}
                  <DepartmentPicker
                    label="Rehire department (required)"
                    id="rehire-department"
                    value={values.department}
                    onChange={(department) =>
                      setValues({ ...values, department })
                    }
                    error={fields.department}
                    disabled={busy}
                  />
                  {field("location", "Rehire location")}
                  <ManagerPicker
                    id="rehire-managerId"
                    value={values.managerId}
                    onChange={(managerId) =>
                      setValues({ ...values, managerId })
                    }
                    disabled={busy}
                    error={fields.managerId}
                  />
                </div>
              ) : (
                <label>
                  Rehire decision (required)
                  <select
                    value={decision}
                    onChange={(e) => setDecision(e.target.value)}
                  >
                    <option value="APPROVED" disabled={!canApprove}>
                      Approve into onboarding
                    </option>
                    <option value="REJECTED">Reject rehire</option>
                  </select>
                </label>
              )}
              <label htmlFor="rehire-reason">Rehire reason (required)</label>
              <textarea
                id="rehire-reason"
                required
                maxLength={500}
                value={values.reason}
                aria-invalid={!!fields.reason}
                aria-describedby={
                  fields.reason ? "rehire-reason-error" : undefined
                }
                onChange={(e) =>
                  setValues({ ...values, reason: e.target.value })
                }
              />
              {fields.reason && (
                <p className="field-error" id="rehire-reason-error">
                  {fields.reason}
                </p>
              )}
              <p>
                {mode === "request"
                  ? "Submit for independent HR review. Use today through one year from today; prior employment remains unchanged."
                  : decision === "APPROVED"
                    ? `Approve employee ${employeeId} into a new Onboarding episode from ${pending!.startDate}. Preserve the original episode and contact history; reset position assignment. Legacy access stays blocked and onboarding, payroll and identity handoffs remain pending.`
                    : "Reject rehire; do not create a new episode or restore access."}
              </p>
              <div className="form-footer">
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() => {
                    if (
                      !Object.values(values).some(Boolean) ||
                      window.confirm("Discard unsaved rehire values?")
                    ) {
                      setMode(null);
                      setValues(empty);
                      onDirty(false);
                    }
                  }}
                >
                  Cancel rehire form
                </button>
                <button className="button primary" disabled={busy}>
                  {busy
                    ? "Saving…"
                    : mode === "request"
                      ? "Submit rehire request"
                      : "Record rehire decision"}
                </button>
              </div>
            </form>
          )}
        </>
      )}
    </section>
  );
}
