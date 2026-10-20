"use client";
import { useState } from "react";
import type { Sheet } from "./contracts";
import { hours } from "./contracts";
import { TimeError, useTimeCommand, useTimeDraft } from "./shared";
export function BulkReview({
  sheets,
  onSaved,
}: {
  sheets: Sheet[];
  onSaved: () => void;
}) {
  const [selected, setSelected] = useState<string[]>([]),
    [action, setAction] = useState("APPROVE"),
    [comment, setComment] = useState(""),
    [confirmed, setConfirmed] = useState(false);
  const c = useTimeCommand();
  useTimeDraft(selected.length > 0 || comment.length > 0);
  const candidates = sheets.filter((s) => s.status === "SUBMITTED");
  if (!candidates.length) return null;
  return (
    <form
      className="change-form"
      aria-label="Review selected timesheets"
      noValidate
      onChange={() => setConfirmed(false)}
      onSubmit={async (e) => {
        e.preventDefault();
        if (!confirmed || !selected.length) return;
        const result = await c.run<Sheet[]>(
          "/api/v1/time/sheets/selected-actions",
          {
            sheets: candidates
              .filter((s) => selected.includes(s.id))
              .map((s) => ({ id: s.id, version: s.version })),
            action,
            comment,
          },
        );
        if (result) onSaved();
      }}
    >
      <h3>Review selected timesheets</h3>
      <p>
        Only checked submissions on this page are included. All decisions
        succeed together or none are saved. Maximum 20. This does not act on all
        filtered results.
      </p>
      <TimeError message={c.error} fields={c.fields} />
      <fieldset disabled={c.busy}>
        <legend>Select submissions</legend>
        {candidates.map((s) => (
          <label className="removal-choice" key={s.id}>
            <input
              type="checkbox"
              checked={selected.includes(s.id)}
              onChange={(e) =>
                setSelected((old) =>
                  e.target.checked
                    ? [...old, s.id]
                    : old.filter((id) => id !== s.id),
                )
              }
            />
            {s.employeeId} · {s.week} · {hours(s.totalMinutes)}
          </label>
        ))}
      </fieldset>
      <label>
        Batch review action
        <select value={action} onChange={(e) => setAction(e.target.value)}>
          <option value="APPROVE">Approve hours</option>
          <option value="RETURN">Return for correction</option>
          <option value="REJECT">Reject submission</option>
        </select>
      </label>
      <label>
        Batch review reason *
        <textarea
          required
          maxLength={500}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
        />
      </label>
      <p>
        {selected.length} selected ·{" "}
        {hours(
          candidates
            .filter((s) => selected.includes(s.id))
            .reduce((n, s) => n + s.totalMinutes, 0),
        )}
        .{" "}
        {action === "APPROVE"
          ? "Approval permanently posts these working minutes to the hours ledger."
          : "No hours will be posted."}
      </p>
      <label className="removal-choice">
        <input
          type="checkbox"
          checked={confirmed}
          onChange={(e) => {
            e.stopPropagation();
            setConfirmed(e.target.checked);
          }}
        />
        I reviewed each selected submission and its impact.
      </label>
      <button
        className="button primary"
        disabled={c.busy || !confirmed || !selected.length}
      >
        Confirm selected timesheets
      </button>
    </form>
  );
}
