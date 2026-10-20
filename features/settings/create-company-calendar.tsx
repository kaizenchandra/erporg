"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import type { CalendarEntry } from "./calendar-contracts";
import { weekdays } from "./calendar-contracts";
export function CreateCompanyCalendar({
  zones,
  onClose,
  onSaved,
}: {
  zones: string[];
  onClose: () => void;
  onSaved: (e: CalendarEntry) => void;
}) {
  const [today] = useState(() => new Date().toISOString().slice(0, 10));
  const initial = {
    code: "",
    name: "",
    timeZone: "",
    effectiveFrom: today,
    effectiveUntil: today,
  };
  const [calendar, setCalendar] = useState(initial),
    [days, setDays] = useState<number[]>([1, 2, 3, 4, 5]),
    [closures, setClosures] = useState(""),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [errors, setErrors] = useState<Record<string, string>>({}),
    [busy, setBusy] = useState(false);
  const attempt = useRef<{ fingerprint: string; key: string } | null>(null),
    summary = useRef<HTMLParagraphElement>(null),
    id = useId();
  const dirty =
    JSON.stringify(calendar) !== JSON.stringify(initial) ||
    JSON.stringify(days) !== "[1,2,3,4,5]" ||
    closures !== "" ||
    reason !== "";
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [dirty]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const invalid = (field: string) => ({
    "aria-invalid": !!errors[field],
    "aria-describedby": errors[field] ? `${id}-${field}` : undefined,
  });
  const fieldError = (field: string) =>
    errors[field] ? (
      <span className="error" id={`${id}-${field}`}>
        {errors[field]}
      </span>
    ) : null;
  return (
    <Modal
      title="Create company calendar"
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          (!dirty || window.confirm("Discard unsaved company calendar?"))
        )
          onClose();
      }}
    >
      <form
        className="change-form legal-entity-form"
        aria-label="Company calendar form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setErrors({});
          const body = {
            calendar: {
              ...calendar,
              workingDays: days,
              closedDates:
                closures.trim() === "" ? [] : closures.split(/\r?\n/),
            },
            reason,
          };
          const fingerprint = JSON.stringify(body);
          if (attempt.current?.fingerprint !== fingerprint)
            attempt.current = { fingerprint, key: crypto.randomUUID() };
          try {
            onSaved(
              await api<CalendarEntry>("/api/v1/company-calendars", {
                method: "POST",
                body: JSON.stringify({
                  ...body,
                  idempotencyKey: attempt.current.key,
                }),
              }),
            );
          } catch (e) {
            if (e instanceof ApiError) setErrors(e.fieldErrors);
            setError(
              (e instanceof Error ? e.message : "Unable to record calendar") +
                " Entries are preserved. Retry unchanged values to recover a recorded calendar, or close and refresh.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          Record company working policy for the displayed validity period.
          Closure dates override working weekdays. This does not configure
          statutory holidays, assign employees or change leave or payroll. The
          creation snapshot and audit are retained. Changes use dated
          amendments; earlier policy remains inspectable.
        </p>
        {error && (
          <p
            ref={summary}
            tabIndex={-1}
            role="alert"
            aria-label="Company calendar errors"
            className="error"
          >
            {error}
          </p>
        )}
        <fieldset disabled={busy} className="form-grid">
          {(
            [
              ["code", "Calendar code", 30],
              ["name", "Calendar name", 120],
              ["effectiveFrom", "Valid from", 10],
              ["effectiveUntil", "Valid through", 10],
            ] as const
          ).map(([field, label, max]) => (
            <label key={field}>
              {label} *
              <input
                required
                maxLength={max}
                type={field.startsWith("effective") ? "date" : "text"}
                value={calendar[field]}
                {...invalid(field)}
                onChange={(e) =>
                  setCalendar((c) => ({ ...c, [field]: e.target.value }))
                }
              />
              {fieldError(field)}
            </label>
          ))}
          <label>
            Calendar time zone *
            <input
              required
              list={`${id}-zones`}
              value={calendar.timeZone}
              maxLength={80}
              {...invalid("timeZone")}
              onChange={(e) =>
                setCalendar((c) => ({ ...c, timeZone: e.target.value }))
              }
            />
            <datalist id={`${id}-zones`}>
              {zones.map((z) => (
                <option key={z} value={z} />
              ))}
            </datalist>
            {fieldError("timeZone")}
          </label>
          <fieldset className="calendar-weekdays" {...invalid("workingDays")}>
            <legend>Working weekdays *</legend>
            {weekdays.map((d, i) => (
              <label key={d}>
                <input
                  type="checkbox"
                  checked={days.includes(i + 1)}
                  onChange={(e) =>
                    setDays((v) =>
                      e.target.checked
                        ? [...v, i + 1].sort((a, b) => a - b)
                        : v.filter((x) => x !== i + 1),
                    )
                  }
                />
                {d}
              </label>
            ))}
            {fieldError("workingDays")}
          </fieldset>
          <label>
            Company closure dates
            <textarea
              rows={4}
              maxLength={1000}
              value={closures}
              {...invalid("closedDates")}
              onChange={(e) => setClosures(e.target.value)}
            />
            <span className="muted">
              Optional: one YYYY-MM-DD date per line, up to 50 dates within the
              validity period.
            </span>
            {fieldError("closedDates")}
          </label>
          <label>
            Reason for calendar *
            <textarea
              required
              maxLength={500}
              value={reason}
              {...invalid("reason")}
              onChange={(e) => setReason(e.target.value)}
            />
            {fieldError("reason")}
          </label>
        </fieldset>
        <p>
          Required fields are marked *. Dates are local calendar dates in the
          declared time zone; creation date validation uses UTC.
        </p>
        <button className="button primary" disabled={busy}>
          {busy ? "Recording…" : "Record company calendar"}
        </button>
      </form>
    </Modal>
  );
}
