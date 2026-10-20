"use client";
import { useId, useState } from "react";
import { Modal } from "@/components/ui/primitives";
import type { Sheet } from "./contracts";
import { TimeError, useTimeCommand, useTimeDraft } from "./shared";
export function monday(date = new Date()) {
  const d = new Date(date);
  d.setUTCHours(0, 0, 0, 0);
  d.setUTCDate(d.getUTCDate() - ((d.getUTCDay() + 6) % 7));
  return d.toISOString().slice(0, 10);
}
function days(week: string) {
  const start = new Date(`${week}T00:00:00Z`);
  if (!Number.isFinite(start.getTime())) return [];
  return Array.from({ length: 7 }, (_, i) => {
    const d = new Date(start);
    d.setUTCDate(d.getUTCDate() + i);
    return d.toISOString().slice(0, 10);
  });
}
export function SheetEditor({
  sheet,
  onClose,
  onSaved,
}: {
  sheet: Sheet | null;
  onClose: () => void;
  onSaved: (s: Sheet) => void;
}) {
  const id = useId(),
    c = useTimeCommand();
  const [week, setWeek] = useState(sheet?.week ?? monday()),
    [zone, setZone] = useState(sheet?.zone ?? "Etc/UTC"),
    [reason, setReason] = useState(""),
    [dirty, setDirty] = useState(false);
  const [entries, setEntries] = useState<
    Record<string, { minutes: string; code: string }>
  >(() =>
    Object.fromEntries(
      sheet?.entries.map((e) => [
        e.date,
        { minutes: String(e.minutes), code: e.code },
      ]) ?? [],
    ),
  );
  useTimeDraft(dirty);
  const close = () => {
    if (
      !c.busy &&
      (!dirty || window.confirm("Discard this unsaved timesheet?"))
    )
      onClose();
  };
  return (
    <Modal
      title={sheet ? "Edit timesheet" : "New weekly timesheet"}
      onClose={close}
      closeDisabled={c.busy}
      wide
    >
      <form
        className="change-form time-sheet-form"
        noValidate
        onChange={() => setDirty(true)}
        onSubmit={async (e) => {
          e.preventDefault();
          const result = await c.run<Sheet>("/api/v1/time/sheets", {
            week,
            zone,
            version: sheet?.version ?? 0,
            reason,
            entries: days(week).map((date) => ({
              date,
              minutes:
                entries[date]?.minutes === ""
                  ? null
                  : Number(entries[date]?.minutes ?? 0),
              code: entries[date]?.code ?? "",
            })),
          });
          if (result) {
            setDirty(false);
            onSaved(result);
          }
        }}
      >
        <p>
          Enter actual working hours for this Monday–Sunday week. Use whole
          minutes (480 = 8 hours), up to 1,440 per day. Activity codes are
          supplied labels for the approved-hours costing ledger; monetary
          project mappings are not configured. Attendance is separate supporting
          evidence.
        </p>
        <TimeError message={c.error} fields={c.fields} />
        <div className="time-form-grid">
          <label htmlFor={`${id}-week`}>
            Week starting Monday *
            <input
              id={`${id}-week`}
              type="date"
              value={week}
              disabled={!!sheet}
              required
              onChange={(e) => setWeek(e.target.value)}
              aria-invalid={!!c.fields.week}
            />
            {c.fields.week && <span className="error">{c.fields.week}</span>}
          </label>
          <label htmlFor={`${id}-zone`}>
            Timesheet time zone *
            <input
              id={`${id}-zone`}
              value={zone}
              required
              onChange={(e) => setZone(e.target.value)}
              aria-invalid={!!c.fields.zone}
            />
            {c.fields.zone && <span className="error">{c.fields.zone}</span>}
          </label>
        </div>
        <fieldset className="time-days">
          <legend>Daily working time</legend>
          {days(week).map((date) => (
            <div className="time-day" key={date}>
              <strong>{date}</strong>
              <label>
                Working minutes for {date}
                <input
                  type="number"
                  min="0"
                  max="1440"
                  step="1"
                  value={entries[date]?.minutes ?? "0"}
                  onChange={(e) =>
                    setEntries((old) => ({
                      ...old,
                      [date]: {
                        minutes: e.target.value,
                        code: old[date]?.code ?? "",
                      },
                    }))
                  }
                  aria-invalid={!!c.fields.minutes}
                />
              </label>
              <label>
                Activity code for {date}
                <input
                  maxLength={80}
                  value={entries[date]?.code ?? ""}
                  onChange={(e) =>
                    setEntries((old) => ({
                      ...old,
                      [date]: {
                        minutes: old[date]?.minutes ?? "0",
                        code: e.target.value,
                      },
                    }))
                  }
                  aria-invalid={!!c.fields.code}
                />
              </label>
            </div>
          ))}
        </fieldset>
        <label htmlFor={`${id}-reason`}>
          Timesheet reason *
          <textarea
            id={`${id}-reason`}
            value={reason}
            required
            maxLength={500}
            onChange={(e) => setReason(e.target.value)}
            aria-invalid={!!c.fields.reason}
          />
          {c.fields.reason && <span className="error">{c.fields.reason}</span>}
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            disabled={c.busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={c.busy}>
            Save timesheet draft
          </button>
        </div>
      </form>
    </Modal>
  );
}
