"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import { weekdays } from "./calendar-contracts";
import type {
  CalendarAmendmentContext,
  CalendarRevisionEntry,
} from "./calendar-amendment-contracts";
export function AmendCompanyCalendar({
  code,
  onClose,
  onSaved,
}: {
  code: string;
  onClose: () => void;
  onSaved: (e: CalendarRevisionEntry) => void;
}) {
  const [revision, setRevision] = useState(0),
    [context, setContext] = useState<CalendarAmendmentContext>(),
    [loadError, setLoadError] = useState(""),
    [date, setDate] = useState(""),
    [days, setDays] = useState<number[]>([]),
    [closures, setClosures] = useState(""),
    [reason, setReason] = useState(""),
    [review, setReview] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [errors, setErrors] = useState<Record<string, string>>({});
  const id = useId(),
    errorRef = useRef<HTMLParagraphElement>(null),
    loadRef = useRef<HTMLParagraphElement>(null),
    reviewRef = useRef<HTMLHeadingElement>(null),
    attempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const initial = context?.draft;
  const dirty =
    !!initial &&
    (date !== initial.effectiveDate ||
      JSON.stringify(days) !== JSON.stringify(initial.workingDays) ||
      closures !== initial.closedDates.join("\n") ||
      reason !== "");
  useEffect(() => {
    const c = new AbortController();
    api<CalendarAmendmentContext>(
      `/api/v1/company-calendars/${encodeURIComponent(code)}/amendments`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((v) => {
        if (!c.signal.aborted) {
          setContext(v);
          setLoadError("");
          if (v.draft) {
            setDate(v.draft.effectiveDate);
            setDays(v.draft.workingDays);
            setClosures(v.draft.closedDates.join("\n"));
          }
        }
      })
      .catch(() => {
        if (!c.signal.aborted)
          setLoadError(
            "Calendar amendment context could not be loaded. Retry to refresh.",
          );
      });
    return () => c.abort();
  }, [code, revision]);
  useEffect(() => {
    if (loadError) loadRef.current?.focus();
    if (error) errorRef.current?.focus();
    if (review) reviewRef.current?.focus();
  }, [loadError, error, review]);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [dirty]);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard unsaved calendar amendment?"))
    )
      onClose();
  };
  const invalid = (f: string) => ({
    "aria-invalid": !!errors[f],
    "aria-describedby": errors[f] ? `${id}-${f}` : undefined,
  });
  const fieldError = (f: string) =>
    errors[f] ? (
      <span className="error" id={`${id}-${f}`}>
        {errors[f]}
      </span>
    ) : null;
  const submit = async () => {
    if (busy || !context) return;
    setBusy(true);
    setError("");
    setErrors({});
    const body = {
      version: context.version,
      effectiveDate: date,
      workingDays: days,
      closedDates: closures.trim() === "" ? [] : closures.split(/\r?\n/),
      reason,
    };
    const fingerprint = JSON.stringify(body);
    if (attempt.current?.fingerprint !== fingerprint)
      attempt.current = { fingerprint, key: crypto.randomUUID() };
    try {
      onSaved(
        await api<CalendarRevisionEntry>(
          `/api/v1/company-calendars/${encodeURIComponent(code)}/amendments`,
          {
            method: "POST",
            body: JSON.stringify({
              ...body,
              idempotencyKey: attempt.current.key,
            }),
          },
        ),
      );
    } catch (e) {
      if (e instanceof ApiError) setErrors(e.fieldErrors);
      setReview(false);
      setError(
        (e instanceof Error ? e.message : "Unable to record amendment") +
          " Entries are preserved. Retry unchanged values for an uncertain outcome; for a stale version, close and reopen to review the latest policy.",
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={`Amend company calendar · ${code}`}
      closeDisabled={busy}
      onClose={close}
    >
      {loadError ? (
        <p role="alert" ref={loadRef} tabIndex={-1}>
          {loadError}
          <button
            className="button secondary"
            onClick={() => {
              setLoadError("");
              setRevision((n) => n + 1);
            }}
          >
            Retry amendment context
          </button>
        </p>
      ) : !context ? (
        <p role="status">Loading amendment context…</p>
      ) : !context.draft ? (
        <div className="change-form">
          <p>
            No later amendment date is available within the calendar validity,
            sequence or twenty-amendment limit. Existing policy and history
            remain available.
          </p>
        </div>
      ) : (
        <form
          className="change-form legal-entity-form"
          aria-label="Calendar amendment form"
          noValidate
          onSubmit={(e) => {
            e.preventDefault();
            if (review) void submit();
            else {
              setError("");
              setErrors({});
              setReview(true);
            }
          }}
        >
          <p>
            {context.base.calendar.name} · {code} · version {context.version}.
            Replace working weekdays and company closures from the selected
            date. Earlier results, metadata and creation audit are retained. No
            employee assignment, statutory, leave or payroll action occurs.
          </p>
          {error && (
            <p
              ref={errorRef}
              role="alert"
              tabIndex={-1}
              aria-label="Calendar amendment errors"
              className="error"
            >
              {error}
            </p>
          )}
          {review ? (
            <>
              <h3 tabIndex={-1} ref={reviewRef}>
                Review calendar amendment
              </h3>
              <p>
                Calendar: {code}. Effective date: {date || "Not provided"}.
              </p>
              <p>
                Replacement weekdays:{" "}
                {days.map((d) => weekdays[d - 1]).join(", ") || "None selected"}
                .
              </p>
              <p>
                Replacement company closures:{" "}
                {closures ||
                  "None — no company closures in this replacement policy"}
                .
              </p>
              <p>
                This is a complete replacement for dates on/after the effective
                date. Dates before it keep their prior policy. There is no
                amendment cancellation or same-date replacement workflow.
              </p>
              <p>Reason: {reason || "Not provided"}</p>
              <button
                type="button"
                className="button secondary"
                disabled={busy}
                onClick={() => setReview(false)}
              >
                Edit amendment
              </button>
              <button className="button primary" disabled={busy}>
                {busy ? "Recording…" : "Confirm calendar amendment"}
              </button>
            </>
          ) : (
            <>
              <fieldset disabled={busy}>
                <label>
                  Amendment effective date *
                  <input
                    type="date"
                    required
                    value={date}
                    {...invalid("effectiveDate")}
                    onChange={(e) => setDate(e.target.value)}
                  />
                  {fieldError("effectiveDate")}
                </label>
                <fieldset
                  className="calendar-weekdays"
                  {...invalid("workingDays")}
                >
                  <legend>Replacement working weekdays *</legend>
                  {weekdays.map((d, i) => (
                    <label key={d}>
                      <input
                        type="checkbox"
                        checked={days.includes(i + 1)}
                        onChange={(e) =>
                          setDays((v) =>
                            e.target.checked
                              ? [...v, i + 1].sort((a, b) => a - b)
                              : v.filter((d) => d !== i + 1),
                          )
                        }
                      />
                      {d}
                    </label>
                  ))}
                  {fieldError("workingDays")}
                </fieldset>
                <label>
                  Replacement closure dates
                  <textarea
                    value={closures}
                    rows={4}
                    maxLength={1000}
                    {...invalid("closedDates")}
                    onChange={(e) => setClosures(e.target.value)}
                  />
                  <span className="muted">
                    One YYYY-MM-DD date per line, up to 50, from the amendment
                    date through the calendar end. Blank means no closures in
                    the replacement.
                  </span>
                  {fieldError("closedDates")}
                </label>
                <label>
                  Reason for amendment *
                  <textarea
                    required
                    value={reason}
                    maxLength={500}
                    {...invalid("reason")}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  {fieldError("reason")}
                </label>
              </fieldset>
              <button className="button primary" disabled={busy}>
                Review amendment
              </button>
            </>
          )}
        </form>
      )}
    </Modal>
  );
}
