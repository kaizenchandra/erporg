"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import type { CalendarAmendmentContext } from "./calendar-amendment-contracts";
import type { CalendarRetirementEntry } from "./calendar-retirement-contracts";
export function RetireCompanyCalendar({
  code,
  onClose,
  onSaved,
}: {
  code: string;
  onClose: () => void;
  onSaved: (entry: CalendarRetirementEntry) => void;
}) {
  const [context, setContext] = useState<CalendarAmendmentContext>(),
    [revision, setRevision] = useState(0),
    [loadError, setLoadError] = useState(""),
    [date, setDate] = useState(""),
    [reason, setReason] = useState(""),
    [review, setReview] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [errors, setErrors] = useState<Record<string, string>>({});
  const id = useId(),
    summary = useRef<HTMLParagraphElement>(null),
    loadRef = useRef<HTMLParagraphElement>(null),
    reviewRef = useRef<HTMLHeadingElement>(null),
    attempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const dirty =
    !!context?.retirementDraft &&
    (date !== context.retirementDraft || reason !== "");
  useEffect(() => {
    const c = new AbortController();
    api<CalendarAmendmentContext>(
      `/api/v1/company-calendars/${encodeURIComponent(code)}/amendments`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((value) => {
        if (!c.signal.aborted) {
          setContext(value);
          setDate(value.retirementDraft ?? "");
          setLoadError("");
        }
      })
      .catch(() => {
        if (!c.signal.aborted)
          setLoadError(
            "Retirement context could not be loaded. Retry to refresh.",
          );
      });
    return () => c.abort();
  }, [code, revision]);
  useEffect(() => {
    if (loadError) loadRef.current?.focus();
    if (error) summary.current?.focus();
    if (review) reviewRef.current?.focus();
  }, [loadError, error, review]);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [dirty]);
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
    const body = { version: context.version, effectiveDate: date, reason };
    const fingerprint = JSON.stringify(body);
    if (attempt.current?.fingerprint !== fingerprint)
      attempt.current = { fingerprint, key: crypto.randomUUID() };
    try {
      onSaved(
        await api<CalendarRetirementEntry>(
          `/api/v1/company-calendars/${encodeURIComponent(code)}/retirement`,
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
        (e instanceof Error ? e.message : "Unable to retire calendar") +
          " Entries are preserved. Retry unchanged values after an uncertain outcome; for a stale version, close and reopen to inspect the latest policy.",
      );
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      title={`Retire company calendar · ${code}`}
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          (!dirty || window.confirm("Discard unsaved calendar retirement?"))
        )
          onClose();
      }}
    >
      {loadError ? (
        <p ref={loadRef} tabIndex={-1} role="alert">
          {loadError}
          <button
            className="button secondary"
            onClick={() => {
              setLoadError("");
              setRevision((n) => n + 1);
            }}
          >
            Retry retirement context
          </button>
        </p>
      ) : !context ? (
        <p role="status">Loading retirement context…</p>
      ) : !context.retirementDraft ? (
        <div className="change-form">
          <p>
            {context.retirement
              ? "Calendar retirement is already recorded."
              : "No eligible retirement date remains within validity and sequence limits."}{" "}
            Retained history remains available.
          </p>
        </div>
      ) : (
        <form
          className="change-form legal-entity-form"
          aria-label="Calendar retirement form"
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
            {context.base.calendar.name} · {code} · amendment version{" "}
            {context.version}. Retire this unassigned company calendar from the
            chosen date. This records a terminal intent: no new amendments,
            cancellation or reactivation. Earlier date results and all history
            are retained.
          </p>
          {error && (
            <p
              role="alert"
              tabIndex={-1}
              ref={summary}
              aria-label="Calendar retirement errors"
              className="error"
            >
              {error}
            </p>
          )}
          {review ? (
            <>
              <h3 tabIndex={-1} ref={reviewRef}>
                Review calendar retirement
              </h3>
              <p>
                Calendar: {code}. Retirement date: {date || "Not provided"}.
                Expected amendment version: {context.version}.
              </p>
              <p>
                New amendments stop when retirement is recorded. Date previews
                on/after retirement become unavailable; this does not mean a
                non-working day. There is no cancellation or reactivation
                workflow.
              </p>
              <p>
                No employee assignment, statutory holiday, leave or payroll
                action occurs. Creation, amendments, actor/reason and earlier
                results remain inspectable.
              </p>
              <p>Reason: {reason || "Not provided"}</p>
              <button
                type="button"
                className="button secondary"
                disabled={busy}
                onClick={() => setReview(false)}
              >
                Edit retirement
              </button>
              <button className="button primary" disabled={busy}>
                {busy ? "Recording…" : "Confirm calendar retirement"}
              </button>
            </>
          ) : (
            <>
              <fieldset disabled={busy}>
                <label>
                  Retirement effective date *
                  <input
                    type="date"
                    required
                    value={date}
                    {...invalid("effectiveDate")}
                    onChange={(e) => setDate(e.target.value)}
                  />
                  {fieldError("effectiveDate")}
                </label>
                <label>
                  Reason for retirement *
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
              <button className="button primary" disabled={busy}>
                Review retirement
              </button>
            </>
          )}
        </form>
      )}
    </Modal>
  );
}
