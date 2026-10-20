"use client";
import { useState } from "react";
import type { Sheet } from "./contracts";
import { hours } from "./contracts";
import { TimeError, type useTimeCommand } from "./shared";
export function CopySheet({
  sheet,
  onSaved,
  command: c,
  onDirty,
}: {
  sheet: Sheet;
  command: ReturnType<typeof useTimeCommand>;
  onDirty: () => void;
  onSaved: (s: Sheet) => void;
}) {
  const next = new Date(`${sheet.week}T00:00:00Z`);
  next.setUTCDate(next.getUTCDate() + 7);
  const [week, setWeek] = useState(next.toISOString().slice(0, 10)),
    [reason, setReason] = useState(""),
    [confirmed, setConfirmed] = useState(false);
  return (
    <details>
      <summary>Copy this week to a new draft</summary>
      <form
        className="change-form"
        onChange={onDirty}
        onSubmit={async (e) => {
          e.preventDefault();
          if (!confirmed) return;
          const result = await c.run<Sheet>("/api/v1/time/sheets/copy", {
            sourceId: sheet.id,
            sourceVersion: sheet.version,
            week,
            reason,
          });
          if (result) onSaved(result);
        }}
      >
        <p>
          Copy {hours(sheet.totalMinutes)} to corresponding weekdays in a later
          week, using {sheet.zone}. This creates a draft and posts no hours.
          Existing target sheets are never overwritten. Review actual work
          before submission; activity labels are not validated assignments.
        </p>
        <TimeError message={c.error} fields={c.fields} />
        <label>
          Copy to reporting Monday
          <input
            type="date"
            required
            value={week}
            onChange={(e) => {
              setWeek(e.target.value);
              setConfirmed(false);
            }}
          />
        </label>
        <label>
          Copy reason
          <textarea
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <label className="removal-choice">
          <input
            type="checkbox"
            checked={confirmed}
            onChange={(e) => setConfirmed(e.target.checked)}
          />
          I will review copied hours against actual work before submitting.
        </label>
        <button className="button secondary" disabled={c.busy || !confirmed}>
          Create copied draft
        </button>
      </form>
    </details>
  );
}
