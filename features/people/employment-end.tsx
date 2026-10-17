"use client";
import { useEffect, useRef, useState } from "react";
import type { Actor } from "@/lib/contracts";
import { EndWithdrawalPanel, type EndWithdrawal } from "./end-withdrawal";
import { EndReschedulingPanel } from "./end-rescheduling";
import { api, ApiError } from "@/lib/api";
import { formatDate } from "@/components/ui/primitives";
export type EmploymentEndRecord = {
  requestId: string;
  employeeId: string;
  lastWorkingDate: string;
  inactiveDate: string;
  actor: string;
  actorEmployeeId: string;
  reason: string;
  inputVersion: number;
  newVersion: number;
  oldStatus: string;
  newStatus: "Ended";
  recordedAt: string;
  correlationId: string;
};
export type EmploymentEndContext = {
  record: EmploymentEndRecord | null;
  currentRecord: EmploymentEndRecord | null;
  replaced: boolean;
  replacementReviewOpen: boolean;
  phase: "NOT_SCHEDULED" | "SCHEDULED" | "EFFECTIVE" | "WITHDRAWN";
  withdrawals: EndWithdrawal[];
  inactiveDate: string;
  employeeVersion: number;
  openTasks: number;
};
export function EmploymentEndPanel({
  requestId,
  actor,
  employeeId,
  canConfirm,
  hasPendingTaskChanges,
  onDirty,
  onBusy,
  onRecorded,
}: {
  requestId: string;
  actor: Actor;
  employeeId: string;
  canConfirm: boolean;
  hasPendingTaskChanges: boolean;
  onDirty: (dirty: boolean) => void;
  onBusy: (busy: boolean) => void;
  onRecorded: (recorded: boolean) => void;
}) {
  const [data, setData] = useState<EmploymentEndContext | null>(null),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0),
    [notice, setNotice] = useState("");
  const [editing, setEditing] = useState(false),
    [reason, setReason] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [busy, setBusy] = useState(false);
  const [withdrawalDirty, setWithdrawalDirty] = useState(false),
    [replacementDirty, setReplacementDirty] = useState(false),
    [withdrawalBusy, setWithdrawalBusy] = useState(false),
    [replacementBusy, setReplacementBusy] = useState(false);
  useEffect(
    () => onDirty(!!reason || withdrawalDirty || replacementDirty),
    [reason, withdrawalDirty, replacementDirty, onDirty],
  );
  useEffect(
    () => onBusy(busy || withdrawalBusy || replacementBusy),
    [busy, withdrawalBusy, replacementBusy, onBusy],
  );
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null),
    heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    api<EmploymentEndContext>(
      `/api/v1/resignations/${requestId}/employment-end`,
      { signal: controller.signal },
    )
      .then((r) => {
        if (!controller.signal.aborted) {
          setData(r);
          onRecorded(r.phase === "SCHEDULED" || r.phase === "EFFECTIVE");
        }
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error ? e.message : "Unable to load employment end.",
          );
      });
    return () => controller.abort();
  }, [requestId, revision, onRecorded, onDirty]);
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
  const reload = () => {
    setData(null);
    setError("");
    setRevision(revision + 1);
  };
  return (
    <section className="change-form" aria-label="Employment end">
      <h3 ref={heading} tabIndex={-1}>
        Employment end and local access
      </h3>
      <p>
        The approved last working day is inclusive. Local inactivity starts the
        following day at 00:00 UTC, a provisional company convention.
      </p>
      {notice && <p role="status">{notice}</p>}
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to record employment end</strong>
          <p>{error}</p>
          {fields.reason && (
            <p>
              <a
                href="#employment-end-reason"
                onClick={() =>
                  document.getElementById("employment-end-reason")?.focus()
                }
              >
                {fields.reason}
              </a>
            </p>
          )}
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={reload}
          >
            Refresh employment end context
          </button>
        </div>
      )}
      {hasPendingTaskChanges && (
        <p>
          Finish or discard the pending task form before confirming an
          employment end.
        </p>
      )}
      {!data ? (
        <p role="status">Loading employment end…</p>
      ) : data.record ? (
        <>
          <span
            className={`badge ${data.phase === "EFFECTIVE" ? "ended" : "submitted"}`}
          >
            {data.phase === "EFFECTIVE"
              ? "Effective · Ended"
              : data.phase === "WITHDRAWN"
                ? "Withdrawn"
                : "Scheduled"}
          </span>
          <dl className="review-details">
            <div>
              <dt>Employee</dt>
              <dd>{employeeId}</dd>
            </div>
            <div>
              <dt>Last working day</dt>
              <dd>
                {formatDate(
                  (data.currentRecord ?? data.record).lastWorkingDate,
                )}
              </dd>
            </div>
            <div>
              <dt>First inactive date</dt>
              <dd>{formatDate(data.inactiveDate)} · 00:00 UTC</dd>
            </div>
            <div>
              <dt>Recorded by</dt>
              <dd>
                {(data.currentRecord ?? data.record).actor} ·{" "}
                {new Date(
                  (data.currentRecord ?? data.record).recordedAt,
                ).toLocaleString()}
              </dd>
            </div>
            <div>
              <dt>Confirmation reason</dt>
              <dd>{(data.currentRecord ?? data.record).reason}</dd>
            </div>
          </dl>
          {data.phase !== "WITHDRAWN" && (
            <p>
              Local sign-in and authenticated API access are denied from that
              date. Existing sessions are invalidated on their next request.
              Final-pay and external identity-provider handoffs remain pending;
              no external account was disabled or final payment calculated.
            </p>
          )}
          {data.replaced && (
            <p>
              Replacement date approved. Original last working day:{" "}
              {formatDate(data.record.lastWorkingDate)}. Original confirmation
              by {data.record.actor}: {data.record.reason}. Original resignation
              and schedule are retained.
            </p>
          )}
          <EndWithdrawalPanel
            data={data}
            actor={actor}
            blocked={
              hasPendingTaskChanges || replacementDirty || replacementBusy
            }
            onDirty={setWithdrawalDirty}
            onBusy={setWithdrawalBusy}
            onChanged={() => {
              reload();
              heading.current?.focus();
            }}
          />
          <EndReschedulingPanel
            requestId={requestId}
            employeeId={employeeId}
            actor={actor}
            blocked={hasPendingTaskChanges || withdrawalDirty || withdrawalBusy}
            onDirty={setReplacementDirty}
            onBusy={setReplacementBusy}
            onChanged={() => {
              reload();
              heading.current?.focus();
            }}
          />
        </>
      ) : (
        <>
          <p>
            <strong>Employee {employeeId}</strong> · First inactive date:{" "}
            {formatDate(data.inactiveDate)} at 00:00 UTC.
          </p>
          <p>
            {data.openTasks} open case or assigned exit tasks must be resolved.
            Active/planned reporting relationships are validated again at
            confirmation.
          </p>
          {!canConfirm ? (
            <p>Another HR employee must confirm your employment end.</p>
          ) : !editing ? (
            <button
              type="button"
              className="button secondary"
              disabled={data.openTasks > 0 || hasPendingTaskChanges}
              onClick={() => {
                setEditing(true);
                requestAnimationFrame(() =>
                  document.getElementById("employment-end-reason")?.focus(),
                );
              }}
            >
              Review employment end
            </button>
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
                  await api(
                    `/api/v1/resignations/${requestId}/employment-end`,
                    {
                      method: "POST",
                      body: JSON.stringify({
                        reason,
                        version: data.employeeVersion,
                        idempotencyKey: key.current,
                      }),
                    },
                  );
                  setEditing(false);
                  setReason("");
                  onDirty(false);
                  onRecorded(true);
                  setNotice(
                    "Employment end recorded. Local access cutoff is scheduled; downstream handoffs remain pending.",
                  );
                  reload();
                  heading.current?.focus();
                } catch (e) {
                  setError(
                    e instanceof Error
                      ? `${e.message} Your reason is preserved. Refresh context and review the latest state before retrying.`
                      : "Unable to confirm employment end; retry unchanged input.",
                  );
                  if (e instanceof ApiError) setFields(e.fieldErrors);
                } finally {
                  setBusy(false);
                  onBusy(false);
                }
              }}
            >
              <label htmlFor="employment-end-reason">
                Employment end confirmation reason (required)
              </label>
              <textarea
                id="employment-end-reason"
                required
                maxLength={500}
                value={reason}
                aria-invalid={!!fields.reason}
                aria-describedby={
                  fields.reason ? "employment-end-reason-error" : undefined
                }
                onChange={(e) => {
                  setReason(e.target.value);
                  onDirty(true);
                }}
              />
              {fields.reason && (
                <p className="field-error" id="employment-end-reason-error">
                  {fields.reason}
                </p>
              )}
              <p>
                Confirm ending employment for employee {employeeId} from{" "}
                {formatDate(data.inactiveDate)} at 00:00 UTC. This schedules
                Ended status and local sign-in/API denial for every mapped
                identity. It preserves history and queues
                final-pay/external-identity handoffs without executing them.
                Withdrawal requires a separate independent review before the
                cutoff; date amendment is not available.
              </p>
              <div className="form-footer">
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() => {
                    if (
                      !reason ||
                      window.confirm("Discard unsaved employment end reason?")
                    ) {
                      setEditing(false);
                      setReason("");
                      onDirty(false);
                    }
                  }}
                >
                  Cancel employment end form
                </button>
                <button
                  className="button primary"
                  disabled={busy || data.openTasks > 0 || hasPendingTaskChanges}
                >
                  {busy
                    ? "Recording…"
                    : "Confirm employment end and local cutoff"}
                </button>
              </div>
            </form>
          )}
        </>
      )}
    </section>
  );
}
