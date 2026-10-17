"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { formatDate } from "@/components/ui/primitives";
import type { EmploymentEndRecord } from "./employment-end";
export type EndReschedule = {
  id: string;
  requestId: string;
  employeeId: string;
  lastWorkingDate: string;
  priorInactiveDate: string | null;
  oldStatus: string;
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
type Context = {
  currentSchedule: EmploymentEndRecord | null;
  employeeVersion: number;
  withdrawalPending: boolean;
  history: EndReschedule[];
};
export function EndReschedulingPanel({
  requestId,
  employeeId,
  actor,
  blocked,
  onDirty,
  onBusy,
  onChanged,
}: {
  requestId: string;
  employeeId: string;
  actor: Actor;
  blocked: boolean;
  onDirty: (v: boolean) => void;
  onBusy: (v: boolean) => void;
  onChanged: () => void;
}) {
  const [data, setData] = useState<Context | null>(null),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [mode, setMode] = useState<"request" | "review" | null>(null),
    [reason, setReason] = useState(""),
    [date, setDate] = useState(""),
    [decision, setDecision] = useState("APPROVED"),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    api<Context>(
      `/api/v1/resignations/${requestId}/employment-end/rescheduling`,
      { signal: controller.signal },
    )
      .then((r) => {
        if (!controller.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error
              ? e.message
              : "Unable to load replacement-date history.",
          );
      });
    return () => controller.abort();
  }, [requestId, revision]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (reason || date || busy) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [reason, date, busy]);
  const pending = data?.history.find((r) => r.status === "SUBMITTED"),
    approved = data?.history.some((r) => r.status === "APPROVED"),
    own = actor.employeeId === employeeId;
  const canReview =
    pending &&
    !own &&
    pending.actor !== actor.subject &&
    pending.actorEmployeeId !== actor.employeeId;
  const utcToday = new Date().toISOString().slice(0, 10);
  const ended =
    !!data?.currentSchedule && data.currentSchedule.inactiveDate <= utcToday;
  const canApprove =
    !ended &&
    pending?.lastWorkingDate !== undefined &&
    pending.lastWorkingDate >= utcToday &&
    pending.inputVersion === data?.employeeVersion;
  function open(value: "request" | "review") {
    setMode(value);
    onDirty(true);
    key.current = crypto.randomUUID();
    setError("");
    setFields({});
    setDecision(value === "review" && !canApprove ? "REJECTED" : "APPROVED");
    requestAnimationFrame(() =>
      document
        .getElementById(
          value === "request" ? "replacement-last-day" : "replacement-reason",
        )
        ?.focus(),
    );
  }
  return (
    <section aria-label="Employment end replacement date">
      <h4>Correct or reschedule the end date</h4>
      <p>
        Request one independently reviewed replacement date. The current cutoff
        stays in force until approval. A withdrawn schedule has no local cutoff
        until a replacement is approved. This does not amend the employee’s
        original resignation or establish statutory final-pay rules.
      </p>
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to save replacement date</strong>
          <p>{error}</p>
          {Object.entries(fields).map(([f, message]) => (
            <p key={f}>
              <a
                href={
                  f === "lastWorkingDate"
                    ? "#replacement-last-day"
                    : "#replacement-reason"
                }
                onClick={() =>
                  document
                    .getElementById(
                      f === "lastWorkingDate"
                        ? "replacement-last-day"
                        : "replacement-reason",
                    )
                    ?.focus()
                }
              >
                {message}
              </a>
            </p>
          ))}
        </div>
      )}
      {!data ? (
        <>
          <p role="status">Loading replacement-date context…</p>
          {error && (
            <button
              className="button secondary"
              onClick={() => {
                setError("");
                setRevision(revision + 1);
              }}
            >
              Retry replacement context
            </button>
          )}
        </>
      ) : (
        <>
          {data.history.map((r) => (
            <article className="change-form" key={r.id}>
              <strong>
                {r.status} · Last working day {formatDate(r.lastWorkingDate)}
              </strong>
              <p>
                Prior cutoff:{" "}
                {r.priorInactiveDate
                  ? formatDate(r.priorInactiveDate)
                  : "None · schedule withdrawn"}
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
            </article>
          ))}
          {approved ? (
            <p>
              One replacement has been approved. Its local cutoff and pending
              handoffs are current. Original records remain unchanged. Further
              replacement or withdrawal of this replacement requires a later
              workflow.
            </p>
          ) : data.withdrawalPending ? (
            <p>
              Resolve the pending withdrawal before requesting a replacement.
            </p>
          ) : pending ? (
            <p>
              {canReview
                ? "Independent HR review required."
                : "Another HR employee must review this replacement."}{" "}
              Existing access rules remain in force.
            </p>
          ) : ended ? (
            <p>
              The current cutoff has started; this workflow cannot reactivate
              employment.
            </p>
          ) : own ? (
            <p>Another HR employee must request a replacement for you.</p>
          ) : null}
          {!mode ? (
            <>
              {canReview && (
                <button
                  className="button secondary"
                  disabled={blocked}
                  onClick={() => open("review")}
                >
                  Review replacement date
                </button>
              )}
              {!pending &&
                !approved &&
                !own &&
                !ended &&
                !data.withdrawalPending &&
                data.history.length < 20 && (
                  <button
                    className="button secondary"
                    disabled={blocked}
                    onClick={() => open("request")}
                  >
                    Request replacement date
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
                  const base = `/api/v1/resignations/${requestId}/employment-end/rescheduling`;
                  await api(
                    mode === "request" ? base : `${base}/${pending!.id}/review`,
                    {
                      method: "POST",
                      body: JSON.stringify({
                        reason,
                        version: data.employeeVersion,
                        idempotencyKey: key.current,
                        ...(mode === "request"
                          ? { lastWorkingDate: date || null }
                          : { decision }),
                      }),
                    },
                  );
                  setMode(null);
                  setReason("");
                  setDate("");
                  onDirty(false);
                  onChanged();
                } catch (e) {
                  setError(
                    `${e instanceof Error ? e.message : "Save failed."} Entered values are preserved. Reopen the case to refresh context after a conflict.`,
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
                  <label htmlFor="replacement-last-day">
                    Replacement last working day (required)
                  </label>
                  <input
                    id="replacement-last-day"
                    onKeyDown={(e) => {
                      if (e.key === "Escape") {
                        e.preventDefault();
                        e.stopPropagation();
                        document.getElementById("replacement-reason")?.focus();
                      }
                    }}
                    type="date"
                    value={date}
                    required
                    aria-invalid={!!fields.lastWorkingDate}
                    aria-describedby={
                      fields.lastWorkingDate
                        ? "replacement-date-error"
                        : undefined
                    }
                    onChange={(e) => setDate(e.target.value)}
                  />
                  {fields.lastWorkingDate && (
                    <p className="field-error" id="replacement-date-error">
                      {fields.lastWorkingDate}
                    </p>
                  )}
                </>
              ) : (
                <label>
                  Replacement decision (required)
                  <select
                    value={decision}
                    onChange={(e) => setDecision(e.target.value)}
                  >
                    <option value="APPROVED" disabled={!canApprove}>
                      Approve replacement
                    </option>
                    <option value="REJECTED">Reject replacement</option>
                  </select>
                </label>
              )}
              <label htmlFor="replacement-reason">
                Replacement reason (required)
              </label>
              <textarea
                id="replacement-reason"
                required
                maxLength={500}
                value={reason}
                aria-invalid={!!fields.reason}
                aria-describedby={
                  fields.reason ? "replacement-reason-error" : undefined
                }
                onChange={(e) => setReason(e.target.value)}
              />
              {fields.reason && (
                <p className="field-error" id="replacement-reason-error">
                  {fields.reason}
                </p>
              )}
              <p>
                {mode === "request"
                  ? "Submit for independent HR review. Current access rules do not change on submission."
                  : decision === "APPROVED"
                    ? `Approve employee ${employeeId} ending after ${pending!.lastWorkingDate}. Local inactivity starts the following day at 00:00 UTC. Resolve open tasks and reporting handovers first. This replaces the local schedule and creates pending final-pay/external-identity handoffs without executing them.`
                    : "Reject the proposed date; current access rules remain unchanged."}
              </p>
              {mode === "review" && !canApprove && (
                <p>
                  The date, current cutoff or employee snapshot prevents
                  approval. Reject this request and submit a fresh one where
                  permitted.
                </p>
              )}
              <div className="form-footer">
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() => {
                    if (
                      (!reason && !date) ||
                      window.confirm("Discard unsaved replacement-date values?")
                    ) {
                      setMode(null);
                      setReason("");
                      setDate("");
                      onDirty(false);
                    }
                  }}
                >
                  Cancel replacement form
                </button>
                <button className="button primary" disabled={busy || blocked}>
                  {busy
                    ? "Saving…"
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
