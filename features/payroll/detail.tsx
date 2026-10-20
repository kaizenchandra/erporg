"use client";
import { useState } from "react";
import type { Actor } from "@/lib/contracts";
import { Badge, Modal } from "@/components/ui/primitives";
import { amount, type Grant } from "@/features/finance/contracts";
import {
  FinancialError,
  downloadDocument,
  useFinancialCommand,
  useFinancialDraft,
  useFinancialRead,
} from "@/features/finance/shared";
import type { Run } from "./contracts";
export function PayrollDetail({
  id,
  actor,
  grants,
  onClose,
  onSaved,
  onEdit,
}: {
  id: string;
  actor: Actor;
  grants: Grant[];
  onClose: () => void;
  onSaved: () => void;
  onEdit: (r: Run) => void;
}) {
  const result = useFinancialRead<Run>(
      `/api/v1/payroll/runs/${encodeURIComponent(id)}`,
      0,
    ),
    [reason, setReason] = useState(""),
    [action, setAction] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [history, setHistory] = useState(false),
    c = useFinancialCommand();
  useFinancialDraft(reason.length > 0);
  const r = result?.data,
    has = (p: string) =>
      !!r && grants.some((g) => g.entity === r.entity && g.permission === p),
    owner = r?.preparedEmployee === actor.employeeId;
  const actions = r
    ? [
        ...(has("PAYROLL_PREPARE") &&
        owner &&
        ["CALCULATED", "RETURNED"].includes(r.status)
          ? ["SUBMIT"]
          : []),
        ...(has("PAYROLL_APPROVE") &&
        !owner &&
        r.employeeId !== actor.employeeId &&
        r.status === "SUBMITTED"
          ? ["FINALIZE", "RETURN"]
          : []),
        ...(has("PAYROLL_PREPARE") && r.status === "FINALIZED" && !r.journalId
          ? ["CREATE_JOURNAL"]
          : []),
      ]
    : [];
  const close = () => {
    if (!c.busy && (!reason || confirm("Discard unsaved payroll decision?")))
      onClose();
  };
  return (
    <Modal
      title="Payroll result and controls"
      onClose={close}
      closeDisabled={c.busy}
      wide
    >
      {!result ? (
        <p role="status">Loading payroll result…</p>
      ) : result.error ? (
        <FinancialError message={result.error} />
      ) : (
        r && (
          <div className="change-form">
            <h3>
              {r.employeeId} · Week of {r.source.week}
            </h3>
            <Badge status={r.status} />
            <p>
              Run {r.id} · {r.entity} · Revision {r.version}
            </p>
            <p>
              <strong>
                Demonstration result — statutory processing unavailable.
              </strong>
            </p>
            <div className="time-records">
              <article className="time-record">
                <div>
                  <h4>
                    Gross {r.currency} {amount(r.gross)}
                  </h4>
                  <p>
                    {r.source.totalMinutes} approved minutes × {r.hourlyRate}{" "}
                    per hour.
                  </p>
                  <p>
                    Voluntary deduction {amount(r.deduction)} · Net{" "}
                    {amount(r.net)}
                  </p>
                  <p>
                    Tax and employer statutory contributions: not configured.
                  </p>
                </div>
              </article>
            </div>
            <p>Rule {r.ruleVersion}</p>
            <p>
              Cost centre {r.costCentre} · Accounting date {r.postingDate}
            </p>
            <p>
              Source {r.source.id} · Revision {r.source.version} · Employment
              episode {r.episodeStart}
            </p>
            <details>
              <summary>Snapshot of approved daily inputs</summary>
              {r.source.days.map((d) => (
                <p key={d.date}>
                  {d.date} · {d.minutes} minutes · {d.activity || "No work"}
                </p>
              ))}
            </details>
            <p>
              Prepared {r.preparedBy} · Approved{" "}
              {r.approvedBy ?? "Not approved"}
            </p>
            <p>
              Payment processing: {r.paymentStatus}. Finalization is not payment
              settlement.
            </p>
            <p>
              Accounting:{" "}
              {r.journalId
                ? `Draft created: ${r.journalId}. Review and posting are separate finance actions.`
                : "No accounting journal created."}
            </p>
            <p>{r.reason}</p>
            {r.exceptions.map((e) => (
              <p className="error" role="alert" key={e}>
                {e}
              </p>
            ))}
            <FinancialError message={c.error} fields={c.fields} />
            {owner &&
              has("PAYROLL_PREPARE") &&
              ["CALCULATED", "RETURNED"].includes(r.status) && (
                <button
                  className="button secondary"
                  disabled={c.busy}
                  onClick={() => onEdit(r)}
                >
                  Correct calculation inputs
                </button>
              )}
            {r.status === "FINALIZED" && (
              <button
                className="button secondary"
                disabled={c.busy}
                onClick={async () => {
                  const d = await c.run<{ filename: string; content: string }>(
                    `/api/v1/payroll/runs/${r.id}/payslip`,
                    {},
                  );
                  if (d) downloadDocument(d.filename, d.content);
                }}
              >
                Download released demonstration payslip
              </button>
            )}
            {actions.length > 0 && (
              <form
                noValidate
                onSubmit={async (e) => {
                  e.preventDefault();
                  if (!confirmed) return;
                  const saved = await c.run<Run>(
                    `/api/v1/payroll/runs/${r.id}/actions`,
                    {
                      version: r.version,
                      action: action || actions[0],
                      reason,
                    },
                  );
                  if (saved) onSaved();
                }}
              >
                <label>
                  Payroll action
                  <select
                    value={action || actions[0]}
                    onChange={(e) => {
                      setAction(e.target.value);
                      setConfirmed(false);
                    }}
                  >
                    {actions.map((a) => (
                      <option key={a}>{a}</option>
                    ))}
                  </select>
                </label>
                <label>
                  Payroll decision reason *
                  <textarea
                    required
                    maxLength={500}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <p>
                  Finalization freezes this result and releases its
                  demonstration payslip. Accounting creation prepares a balanced
                  journal for separate finance approval and posting. Neither
                  action creates a payment.
                </p>
                <label className="removal-choice">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(e) => setConfirmed(e.target.checked)}
                  />
                  I checked the employee, source snapshot, amounts, exceptions
                  and action impact.
                </label>
                <button
                  className="button primary"
                  disabled={c.busy || !confirmed}
                >
                  Confirm payroll action
                </button>
              </form>
            )}
            <button
              type="button"
              className="button secondary"
              aria-expanded={history}
              onClick={() => setHistory((x) => !x)}
            >
              Payroll audit history
            </button>
            {history && <PayrollHistory id={r.id} />}
          </div>
        )
      )}
    </Modal>
  );
}
function PayrollHistory({ id }: { id: string }) {
  const r = useFinancialRead<
    {
      id: string;
      actor: string;
      action: string;
      reason: string;
      before: string;
      after: string;
      recordedAt: string;
      correlationId: string;
    }[]
  >(`/api/v1/payroll/runs/${id}/history`, 0);
  return (
    <section>
      <h4>Latest 100 audit events</h4>
      {!r ? (
        <p role="status">Loading payroll audit…</p>
      ) : r.error ? (
        <FinancialError message={r.error} />
      ) : (
        r.data!.map((a) => (
          <article className="time-record" key={a.id}>
            <div>
              <h4>
                {a.action} · {a.actor}
              </h4>
              <p>
                {a.reason} · {a.recordedAt} · {a.correlationId}
              </p>
              <details>
                <summary>Audit snapshots</summary>
                <pre>{a.before}</pre>
                <pre>{a.after}</pre>
              </details>
            </div>
          </article>
        ))
      )}
    </section>
  );
}
