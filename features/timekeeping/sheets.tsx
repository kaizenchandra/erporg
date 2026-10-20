"use client";
import { useState } from "react";
import type { Actor } from "@/lib/contracts";
import { Badge, Modal } from "@/components/ui/primitives";
import {
  hours,
  type Page,
  type Sheet,
  type SheetContext,
  type Shift,
  type Audit,
} from "./contracts";
import {
  TimeError,
  TimePager,
  useTimeRead,
  useTimeCommand,
  useTimeDraft,
} from "./shared";
import { CopySheet } from "./copy-sheet";
import { BulkReview } from "./bulk-review";
import { PeriodSummary } from "./periods";
import { SheetEditor } from "./sheet-editor";
export function Timesheets({
  actor,
  review,
}: {
  actor: Actor;
  review: boolean;
}) {
  const [page, setPage] = useState(0),
    [status, setStatus] = useState(review ? "SUBMITTED" : "ALL"),
    [revision, refresh] = useState(0),
    [selected, setSelected] = useState<string | null>(null),
    [edit, setEdit] = useState<Sheet | "new" | null>(null),
    [notice, setNotice] = useState("");
  const result = useTimeRead<Page<Sheet>>(
    `/api/v1/time/sheets?review=${review}&status=${status}&page=${page}`,
    revision,
  );
  return (
    <section
      className="time-panel"
      aria-label={review ? "Timesheet review queue" : "My timesheets"}
    >
      <div className="time-section-header">
        <div>
          <h2>{review ? "Timesheet approvals" : "My timesheets"}</h2>
          <p>
            {review
              ? "Current team scope. Inspect daily hours and attendance evidence before deciding."
              : "Draft, submit and track your working time. Approved hours post once to the costing ledger."}
          </p>
        </div>
        {!review && (
          <button className="button primary" onClick={() => setEdit("new")}>
            New timesheet
          </button>
        )}
      </div>
      <div className="time-clock">
        <label>
          Timesheet status
          <select
            value={status}
            onChange={(e) => {
              if (
                window.dispatchEvent(
                  new Event("atlas-before-navigation", { cancelable: true }),
                )
              ) {
                setPage(0);
                setStatus(e.target.value);
              }
            }}
          >
            {[
              "ALL",
              "DRAFT",
              "SUBMITTED",
              "APPROVED",
              "RETURNED",
              "CORRECTED",
              "REJECTED",
            ].map((s) => (
              <option key={s}>{s}</option>
            ))}
          </select>
        </label>
        <button
          className="button secondary"
          onClick={() => {
            if (
              window.dispatchEvent(
                new Event("atlas-before-navigation", { cancelable: true }),
              )
            )
              refresh((n) => n + 1);
          }}
        >
          Refresh timesheets
        </button>
      </div>
      {notice && <p role="status">{notice}</p>}
      {!result ? (
        <p role="status">Loading timesheets…</p>
      ) : result.error ? (
        <>
          <TimeError message={result.error} />
          <button onClick={() => refresh((n) => n + 1)}>
            Retry timesheets
          </button>
        </>
      ) : (
        <>
          <div className="time-records">
            {result.data!.items.length === 0 && (
              <p>
                {review
                  ? "No timesheets match your current review scope and filter."
                  : "No timesheets match this filter. Start a weekly draft to record your time."}
              </p>
            )}
            {result.data!.items.map((s) => (
              <article className="time-record" key={s.id}>
                <div>
                  <h3>Week of {s.week}</h3>
                  <p>
                    {s.employeeId} · {hours(s.totalMinutes)} · {s.zone}
                  </p>
                  <Badge status={s.status} />
                  {s.comment && <p>Reviewer: {s.comment}</p>}
                </div>
                <button
                  className="button secondary"
                  onClick={() => {
                    if (
                      window.dispatchEvent(
                        new Event("atlas-before-navigation", {
                          cancelable: true,
                        }),
                      )
                    )
                      setSelected(s.id);
                  }}
                >
                  Open timesheet for {s.week} · {s.employeeId}
                </button>
              </article>
            ))}
          </div>
          {review && (
            <BulkReview
              key={`${page}/${status}/${revision}`}
              sheets={result.data!.items}
              onSaved={() => {
                setNotice("Selected timesheet decisions saved.");
                refresh((n) => n + 1);
              }}
            />
          )}
          <TimePager
            page={page}
            total={result.data!.total}
            onPage={(next) => {
              if (
                window.dispatchEvent(
                  new Event("atlas-before-navigation", { cancelable: true }),
                )
              )
                setPage(next);
            }}
          />
        </>
      )}
      {selected && (
        <SheetDetail
          key={selected}
          actor={actor}
          id={selected}
          onClose={() => setSelected(null)}
          onEdit={(s) => {
            setSelected(null);
            setEdit(s);
          }}
          onSaved={(s) => {
            setSelected(null);
            setNotice(`Timesheet ${s.status.toLowerCase()}.`);
            refresh((n) => n + 1);
          }}
        />
      )}
      {edit && (
        <SheetEditor
          sheet={edit === "new" ? null : edit}
          onClose={() => setEdit(null)}
          onSaved={() => {
            setEdit(null);
            setNotice("Timesheet draft saved. Open it to submit for approval.");
            setPage(0);
            refresh((n) => n + 1);
          }}
        />
      )}
    </section>
  );
}
function SheetDetail({
  actor,
  id,
  onClose,
  onEdit,
  onSaved,
}: {
  actor: Actor;
  id: string;
  onClose: () => void;
  onEdit: (s: Sheet) => void;
  onSaved: (s: Sheet) => void;
}) {
  const result = useTimeRead<SheetContext>(
    `/api/v1/time/sheets/${encodeURIComponent(id)}`,
    0,
  );
  const [action, setAction] = useState("APPROVE"),
    [comment, setComment] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [evidence, setEvidence] = useState(false);
  const c = useTimeCommand(),
    copyCommand = useTimeCommand();
  const [copyDirty, setCopyDirty] = useState(false);
  const busy = c.busy || copyCommand.busy;
  const dirty = comment.length > 0 || copyDirty;
  useTimeDraft(dirty);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard unsaved timesheet changes?"))
    )
      onClose();
  };
  const s = result?.data?.sheet,
    owner = s?.employeeId === actor.employeeId;
  const canSubmit =
      owner && !!s && ["DRAFT", "REJECTED", "CORRECTED"].includes(s.status),
    canEdit =
      owner &&
      !!s &&
      ["DRAFT", "REJECTED", "RETURNED", "CORRECTED"].includes(s.status),
    canRecall = owner && s?.status === "SUBMITTED",
    canReview =
      !owner && actor.role !== "EMPLOYEE" && s?.status === "SUBMITTED";
  return (
    <Modal
      title="Timesheet details and costing"
      onClose={close}
      closeDisabled={busy}
      wide
    >
      {!result ? (
        <p role="status" className="form-intro">
          Loading current timesheet…
        </p>
      ) : result.error ? (
        <TimeError message={result.error} />
      ) : (
        <div className="change-form">
          <h3>
            {s!.employeeId} · Week of {s!.week}
          </h3>
          <p>
            {s!.zone} · Revision {s!.version} · Employment episode{" "}
            {s!.episodeStart}
          </p>
          <Badge status={s!.status} />
          <PeriodSummary week={s!.week} />
          <p>
            Working time: <strong>{hours(s!.totalMinutes)}</strong>. Allocated
            to costing: <strong>{hours(result.data!.allocatedMinutes)}</strong>.
          </p>
          <div className="time-daily-summary">
            {s!.entries.map((e) => (
              <div key={e.date}>
                <time>{e.date}</time>
                <strong>{hours(e.minutes)}</strong>
                <span>{e.code || "No work recorded"}</span>
              </div>
            ))}
          </div>
          <p>Employee reason: {s!.reason}</p>
          {owner && (
            <CopySheet
              sheet={s!}
              onSaved={onSaved}
              command={copyCommand}
              onDirty={() => setCopyDirty(true)}
            />
          )}
          {s!.comment && (
            <p>
              Decision by {s!.decidedBy}: {s!.comment}
            </p>
          )}
          {s!.status === "APPROVED" && (
            <section aria-label="Approved hours costing ledger">
              <h3>Posted hours allocations</h3>
              <p>
                {result.data!.allocatedMinutes === s!.totalMinutes
                  ? "Reconciled: allocated minutes equal approved minutes."
                  : "Reconciliation mismatch: contact HR; do not process further."}{" "}
                This ledger contains labour-hour units only. Approved time can
                be selected in the separate demonstration Payroll workflow; this
                view does not report monetary posting or payment status.
              </p>
              {result.data!.allocations.map((a) => (
                <p key={a.date}>
                  {a.date} · {a.code} · {hours(a.minutes)} · Posted {a.postedAt}
                </p>
              ))}
            </section>
          )}
          <button
            type="button"
            className="button secondary"
            aria-expanded={evidence}
            onClick={() => setEvidence((v) => !v)}
          >
            Attendance and audit evidence
          </button>
          {evidence && <Evidence employeeId={s!.employeeId} />}
          {canEdit && (
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={() => onEdit(s!)}
            >
              Edit draft hours
            </button>
          )}
          {(canSubmit || canReview || canRecall) && (
            <form
              className="time-decision"
              noValidate
              onSubmit={async (e) => {
                e.preventDefault();
                if (!confirmed) return;
                const updated = await c.run<Sheet>(
                  `/api/v1/time/sheets/${encodeURIComponent(id)}/actions`,
                  {
                    version: s!.version,
                    action: canRecall
                      ? "RECALL"
                      : canSubmit
                        ? "SUBMIT"
                        : action,
                    comment,
                  },
                );
                if (updated) onSaved(updated);
              }}
            >
              <TimeError message={c.error} fields={c.fields} />
              {canReview && (
                <label>
                  Timesheet decision
                  <select
                    value={action}
                    onChange={(e) => {
                      setAction(e.target.value);
                      setConfirmed(false);
                    }}
                  >
                    <option value="APPROVE">Approve hours</option>
                    <option value="RETURN">Return for correction</option>
                    <option value="REJECT">Reject submission</option>
                  </select>
                </label>
              )}
              <label>
                Timesheet comment *
                <textarea
                  value={comment}
                  maxLength={500}
                  required
                  onChange={(e) => setComment(e.target.value)}
                  aria-invalid={!!c.fields.comment}
                />
                {c.fields.comment && (
                  <span className="error">{c.fields.comment}</span>
                )}
              </label>
              <p>
                {canRecall
                  ? "Recall cancels the pending submission and reopens its draft. It cannot undo an approval or posted hours."
                  : canSubmit
                    ? "Submitting freezes these hours until a reviewer decides."
                    : action === "APPROVE"
                      ? `Approval will permanently post ${s!.totalMinutes} working minutes for ${s!.employeeId}, once, to the hours costing ledger. Approved hours cannot be edited here.`
                      : action === "RETURN"
                        ? "Returning posts no hours. The employee must save a correction and resubmit with your reason visible in audit history."
                        : "Rejection posts no hours and lets the employee revise and resubmit."}
              </p>
              <label className="removal-choice">
                <input
                  type="checkbox"
                  checked={confirmed}
                  onChange={(e) => setConfirmed(e.target.checked)}
                />
                I have checked this timesheet and its impact.
              </label>
              <button className="button primary" disabled={busy || !confirmed}>
                {canRecall
                  ? "Recall timesheet"
                  : canSubmit
                    ? "Submit timesheet for approval"
                    : "Confirm timesheet decision"}
              </button>
            </form>
          )}
        </div>
      )}
    </Modal>
  );
}
export function Evidence({ employeeId }: { employeeId: string }) {
  const [page, setPage] = useState(0),
    [revision, refresh] = useState(0);
  const result = useTimeRead<Page<Audit>>(
    `/api/v1/time/history/${encodeURIComponent(employeeId)}?page=${page}`,
    revision,
  );
  const attendance = useTimeRead<Page<Shift>>(
    `/api/v1/time/attendance/${encodeURIComponent(employeeId)}?size=10`,
    revision,
  );
  return (
    <section className="time-evidence" aria-label="Time audit history">
      <h3>Attendance and time audit · {employeeId}</h3>
      <p>
        Latest 10 attendance shifts are supporting evidence. Use paginated audit
        below for all recorded time changes.
      </p>
      <button type="button" onClick={() => refresh((n) => n + 1)}>
        Refresh time history
      </button>
      {!attendance ? (
        <p role="status">Loading attendance evidence…</p>
      ) : attendance.error ? (
        <TimeError message={attendance.error} />
      ) : (
        attendance.data!.items.map((s) => (
          <p key={s.id}>
            {s.start} → {s.end ?? "Missing clock-out"} · {s.zone}
          </p>
        ))
      )}
      {!result ? (
        <p role="status">Loading audit history…</p>
      ) : result.error ? (
        <TimeError message={result.error} />
      ) : (
        <>
          {result.data!.items.length === 0 && <p>No time activity recorded.</p>}
          {result.data!.items.map((a) => (
            <article className="time-record" key={a.id}>
              <div>
                <h4>
                  {a.action} · {a.actor}
                </h4>
                <p>{a.reason}</p>
                <p>
                  {a.recordedAt} · Correlation {a.correlationId}
                </p>
                <details>
                  <summary>Before and after values</summary>
                  <pre>{a.before}</pre>
                  <pre>{a.after}</pre>
                </details>
              </div>
            </article>
          ))}
          <TimePager page={page} total={result.data!.total} onPage={setPage} />
        </>
      )}
    </section>
  );
}
