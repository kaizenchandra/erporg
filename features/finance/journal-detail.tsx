"use client";
import { useState } from "react";
import { Badge, Modal } from "@/components/ui/primitives";
import type { Actor } from "@/lib/contracts";
import { amount, type Grant, type Journal } from "./contracts";
import {
  FinancialError,
  useFinancialRead,
  useFinancialCommand,
  useFinancialDraft,
} from "./shared";
export function JournalDetail({
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
  onEdit: (j: Journal) => void;
}) {
  const result = useFinancialRead<Journal>(
      `/api/v1/finance/journals/${encodeURIComponent(id)}`,
      0,
    ),
    c = useFinancialCommand(),
    [reason, setReason] = useState(""),
    [action, setAction] = useState(""),
    [confirmed, setConfirmed] = useState(false);
  useFinancialDraft(reason.length > 0);
  const j = result?.data,
    has = (p: string) =>
      !!j && grants.some((g) => g.entity === j.entity && g.permission === p),
    owner = j?.preparedEmployee === actor.employeeId;
  const actions = j
    ? [
        ...(owner &&
        has("PREPARE") &&
        ["DRAFT", "RETURNED", "REJECTED"].includes(j.status)
          ? ["SUBMIT"]
          : []),
        ...(!owner && has("APPROVE") && j.status === "SUBMITTED"
          ? ["APPROVE", "RETURN", "REJECT"]
          : []),
        ...(!owner &&
        j.approvedEmployee !== actor.employeeId &&
        has("POST") &&
        j.status === "APPROVED"
          ? ["POST"]
          : []),
        ...(has("PREPARE") && j.status === "POSTED" && j.sourceType === "MANUAL"
          ? ["REVERSE"]
          : []),
      ]
    : [];
  const close = () => {
    if (!c.busy && (!reason || confirm("Discard unsaved journal decision?")))
      onClose();
  };
  return (
    <Modal
      title="Journal details and approval"
      onClose={close}
      closeDisabled={c.busy}
      wide
    >
      {!result ? (
        <p role="status">Loading journal…</p>
      ) : result.error ? (
        <FinancialError message={result.error} />
      ) : (
        j && (
          <div className="change-form">
            <h3>
              {j.entity} · {j.postingDate}
            </h3>
            <Badge status={j.status} />
            <p>
              Journal {j.id} · Revision {j.version} · {j.currency}
            </p>
            <p>
              Source {j.sourceType} · {j.sourceId}
              {j.reversalOf && ` · Reverses ${j.reversalOf}`}
            </p>
            <p>
              Prepared by {j.preparedBy} · Approved by{" "}
              {j.approvedBy ?? "Pending independent approval"}
            </p>
            <div className="time-records">
              {j.lines.map((l, i) => (
                <article className="time-record" key={i}>
                  <div>
                    <h4>
                      {l.account} · {l.costCentre || "No dimension"}
                    </h4>
                    <p>
                      Debit {amount(l.debit)} · Credit {amount(l.credit)}
                    </p>
                    <p>{l.memo}</p>
                  </div>
                </article>
              ))}
            </div>
            <p>{j.reason}</p>
            {owner &&
              has("PREPARE") &&
              j.sourceType === "MANUAL" &&
              ["DRAFT", "RETURNED", "REJECTED"].includes(j.status) && (
                <button
                  className="button secondary"
                  disabled={c.busy}
                  onClick={() => onEdit(j)}
                >
                  Edit journal
                </button>
              )}
            {actions.length > 0 && (
              <form
                noValidate
                onSubmit={async (e) => {
                  e.preventDefault();
                  if (!confirmed) return;
                  const saved = await c.run<Journal>(
                    `/api/v1/finance/journals/${encodeURIComponent(j.id)}/actions`,
                    {
                      version: j.version,
                      action: action || actions[0],
                      reason,
                    },
                  );
                  if (saved) onSaved();
                }}
              >
                <FinancialError message={c.error} fields={c.fields} />
                <label>
                  Journal action
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
                  Decision reason *
                  <textarea
                    required
                    maxLength={500}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                </label>
                <p>
                  Posting affects this entity ledger only after separate
                  preparation and approval. Reversal prepares a new opposite
                  journal for independent review and posting; it never deletes
                  the original. Payroll corrections must start in payroll.
                </p>
                <label className="removal-choice">
                  <input
                    type="checkbox"
                    checked={confirmed}
                    onChange={(e) => setConfirmed(e.target.checked)}
                  />
                  I reviewed the journal scope, balanced lines, period and
                  financial impact.
                </label>
                <button
                  className="button primary"
                  disabled={c.busy || !confirmed}
                >
                  Confirm journal action
                </button>
              </form>
            )}
          </div>
        )
      )}
    </Modal>
  );
}
