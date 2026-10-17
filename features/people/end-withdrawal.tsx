"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import type { EmploymentEndContext } from "./employment-end";

export type EndWithdrawal = {
  id: string;
  requestId: string;
  employeeId: string;
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
export function EndWithdrawalPanel({
  data,
  actor,
  blocked,
  onDirty,
  onBusy,
  onChanged,
}: {
  data: EmploymentEndContext;
  actor: Actor;
  blocked: boolean;
  onDirty: (v: boolean) => void;
  onBusy: (v: boolean) => void;
  onChanged: () => void;
}) {
  const [mode, setMode] = useState<"request" | "review" | null>(null);
  const [reason, setReason] = useState(""),
    [decision, setDecision] = useState("APPROVED"),
    [error, setError] = useState(""),
    [field, setField] = useState(""),
    [busy, setBusy] = useState(false);
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null);
  const pending = data.withdrawals.find((w) => w.status === "SUBMITTED");
  const own = actor.employeeId === data.record?.employeeId;
  const canReview =
    pending &&
    !own &&
    pending.actor !== actor.subject &&
    pending.actorEmployeeId !== actor.employeeId;
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (reason || busy) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [reason, busy]);
  function open(value: "request" | "review") {
    key.current = crypto.randomUUID();
    setMode(value);
    onDirty(true);
    setError("");
    setField("");
    requestAnimationFrame(() =>
      document.getElementById("end-withdrawal-reason")?.focus(),
    );
  }
  return (
    <section aria-label="Employment end withdrawal">
      <h4>Withdrawal review</h4>
      <p>
        A request leaves the original cutoff in force. Independent HR approval
        before the first inactive date suppresses that schedule and its pending
        handoffs. It does not undo external actions or withdraw the approved
        resignation.
      </p>
      {data.withdrawals.map((w) => (
        <article key={w.id} className="change-form">
          <strong>{w.status}</strong>
          <p>
            Requested by {w.actor} · {new Date(w.recordedAt).toLocaleString()}
          </p>
          <p>{w.reason}</p>
          {w.reviewer && (
            <>
              <p>
                Reviewed by {w.reviewer} ·{" "}
                {new Date(w.reviewedAt!).toLocaleString()}
              </p>
              <p>{w.comment}</p>
            </>
          )}
        </article>
      ))}
      {data.replacementReviewOpen ? (
        <p>
          A pending or approved replacement prevents withdrawal of the original
          schedule. Review the replacement below; the original history is
          retained.
        </p>
      ) : data.phase === "WITHDRAWN" ? (
        <p>
          The original schedule is retained. Local inactivity and its pending
          handoffs are suppressed. Task coordination may resume. Rescheduling
          and end-date correction require a future workflow.
        </p>
      ) : pending ? (
        <p>
          {canReview
            ? "An independent review is required."
            : "Another HR employee must review this request."}{" "}
          The original cutoff remains in force.
        </p>
      ) : data.phase === "EFFECTIVE" ? (
        <p>The cutoff has started. Withdrawal cannot reactivate employment.</p>
      ) : own ? (
        <p>Another HR employee must request withdrawal for you.</p>
      ) : null}
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to save withdrawal</strong>
          <p>{error}</p>
          {field && (
            <a
              href="#end-withdrawal-reason"
              onClick={() =>
                document.getElementById("end-withdrawal-reason")?.focus()
              }
            >
              {field}
            </a>
          )}
        </div>
      )}
      {!mode ? (
        <>
          {canReview && (
            <button
              className="button secondary"
              disabled={blocked}
              onClick={() => open("review")}
            >
              Review end withdrawal
            </button>
          )}
          {!data.replacementReviewOpen &&
            !pending &&
            data.phase === "SCHEDULED" &&
            !own &&
            data.withdrawals.length < 20 && (
              <button
                className="button secondary"
                disabled={blocked}
                onClick={() => open("request")}
              >
                Request end withdrawal
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
            setField("");
            try {
              const base = `/api/v1/resignations/${data.record!.requestId}/employment-end/withdrawals`;
              await api(
                mode === "request" ? base : `${base}/${pending!.id}/review`,
                {
                  method: "POST",
                  body: JSON.stringify({
                    reason,
                    version: data.employeeVersion,
                    idempotencyKey: key.current,
                    ...(mode === "review" ? { decision } : {}),
                  }),
                },
              );
              setMode(null);
              setReason("");
              onDirty(false);
              onChanged();
            } catch (e) {
              setError(
                `${e instanceof Error ? e.message : "Save failed."} Your reason is preserved. Close and reopen the case to refresh context before retrying after a conflict.`,
              );
              if (e instanceof ApiError) setField(e.fieldErrors.reason ?? "");
            } finally {
              setBusy(false);
              onBusy(false);
            }
          }}
        >
          {mode === "review" && (
            <label>
              Withdrawal decision (required)
              <select
                value={decision}
                onChange={(e) => {
                  setDecision(e.target.value);
                  onDirty(true);
                }}
              >
                <option value="APPROVED">Approve withdrawal</option>
                <option value="REJECTED">Reject withdrawal</option>
              </select>
            </label>
          )}
          <label htmlFor="end-withdrawal-reason">
            Withdrawal reason (required)
          </label>
          <textarea
            id="end-withdrawal-reason"
            required
            maxLength={500}
            value={reason}
            aria-invalid={!!field}
            aria-describedby={field ? "withdrawal-reason-error" : undefined}
            onChange={(e) => {
              setReason(e.target.value);
              onDirty(true);
            }}
          />
          {field && (
            <p className="field-error" id="withdrawal-reason-error">
              {field}
            </p>
          )}
          <p>
            {mode === "request"
              ? "Submit for a second HR employee to review. Access and employment stay governed by the original schedule until approval."
              : decision === "APPROVED"
                ? `Approve withdrawal for ${data.record!.employeeId}: suppress the ${data.inactiveDate} local cutoff and both pending handoffs; preserve the original record. No external action is reversed.`
                : "Reject withdrawal: preserve the original schedule and cutoff."}
          </p>
          <div className="form-footer">
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => {
                if (
                  !reason ||
                  window.confirm("Discard unsaved withdrawal reason?")
                ) {
                  setMode(null);
                  setReason("");
                  onDirty(false);
                }
              }}
            >
              Cancel withdrawal form
            </button>
            <button className="button primary" disabled={busy || blocked}>
              {busy
                ? "Saving…"
                : mode === "request"
                  ? "Submit withdrawal request"
                  : "Record withdrawal decision"}
            </button>
          </div>
        </form>
      )}
    </section>
  );
}
