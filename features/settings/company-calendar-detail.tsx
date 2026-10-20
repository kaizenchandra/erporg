"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { Modal, formatDate } from "@/components/ui/primitives";
import type { CalendarPreview } from "./calendar-contracts";
import type { CalendarAmendmentContext } from "./calendar-amendment-contracts";
import { weekdays } from "./calendar-contracts";
export function CompanyCalendarDetail({
  code,
  onClose,
  onAmend,
  onRetire,
  notice,
}: {
  code: string;
  onClose: () => void;
  onAmend: () => void;
  onRetire: () => void;
  notice: string;
}) {
  const [revision, setRevision] = useState(0),
    [result, setResult] = useState<{
      key: string;
      entry?: CalendarAmendmentContext;
      error?: string;
    }>(),
    [date, setDate] = useState(""),
    [query, setQuery] = useState<{ date: string; revision: number } | null>(
      null,
    ),
    [preview, setPreview] = useState<{
      key: string;
      value?: CalendarPreview;
      error?: string;
    }>();
  const errorRef = useRef<HTMLParagraphElement>(null),
    previewError = useRef<HTMLParagraphElement>(null);
  const key = JSON.stringify([code, revision]);
  const current = result?.key === key ? result : undefined;
  const previewKey = JSON.stringify([key, query]);
  const answer = preview?.key === previewKey ? preview : undefined;
  useEffect(() => {
    const c = new AbortController();
    api<CalendarAmendmentContext>(
      `/api/v1/company-calendars/${encodeURIComponent(code)}/amendments`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((entry) => {
        if (!c.signal.aborted) {
          setResult({ key, entry });
          setDate(entry.base.calendar.effectiveFrom);
        }
      })
      .catch(() => {
        if (!c.signal.aborted)
          setResult({
            key,
            error: "Calendar details could not be loaded. Retry to refresh.",
          });
      });
    return () => c.abort();
  }, [key, code]);
  useEffect(() => {
    if (!query) return;
    const c = new AbortController();
    api<CalendarPreview>(
      `/api/v1/company-calendars/${encodeURIComponent(code)}/preview?${new URLSearchParams({ date: query.date })}`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((value) => {
        if (!c.signal.aborted) setPreview({ key: previewKey, value });
      })
      .catch((e) => {
        if (!c.signal.aborted)
          setPreview({
            key: previewKey,
            error:
              (e instanceof Error ? e.message : "Unable to preview date") +
              " Select a date within validity and before retirement, or another calendar.",
          });
      });
    return () => c.abort();
  }, [previewKey, query, code]);
  useEffect(() => {
    if (current?.error) errorRef.current?.focus();
    if (answer?.error) previewError.current?.focus();
  }, [current?.error, answer?.error]);
  return (
    <Modal title={`Company calendar · ${code}`} onClose={onClose}>
      {!current ? (
        <p role="status">Loading calendar details…</p>
      ) : current.error ? (
        <p role="alert" ref={errorRef} tabIndex={-1}>
          {current.error}
          <button
            className="button secondary"
            onClick={() => setRevision((n) => n + 1)}
          >
            Retry calendar detail
          </button>
        </p>
      ) : (
        current.entry && (
          <div className="change-form">
            {notice && (
              <p role="status" className="success">
                {notice}
              </p>
            )}
            <h3>{current.entry.currentPolicy.name}</h3>
            <p>
              Policy as of the current UTC date: revision{" "}
              {current.entry.currentVersion}, effective{" "}
              {formatDate(current.entry.currentEffectiveDate)}. The calendar
              validity and retirement dates still apply.
            </p>
            {current.entry.assignmentRecorded && (
              <p className="muted">
                Approved assignment history freezes this calendar. New
                amendments and retirement are unavailable, including after
                unassignment. Earlier policy remains inspectable.
              </p>
            )}
            <h3>Calendar retirement</h3>
            {current.entry.retirement ? (
              <div>
                <p>
                  {current.entry.retirementStatus === "RETIRED"
                    ? "Retired"
                    : "Scheduled retirement"}{" "}
                  · effective{" "}
                  {formatDate(
                    current.entry.retirement.retirement.effectiveDate,
                  )}
                </p>
                <p>{current.entry.retirement.reason}</p>
                <p>
                  {current.entry.retirement.createdBy} ·{" "}
                  {new Date(
                    current.entry.retirement.recordedAt,
                  ).toLocaleString()}{" "}
                  · Correlation: {current.entry.retirement.correlationId}
                </p>
                <p>
                  New amendments are unavailable. Dates on/after retirement
                  cannot be previewed; earlier results and history remain
                  retained.
                </p>
              </div>
            ) : (
              <>
                <p>No retirement recorded.</p>
                {current.entry.retirementDraft ? (
                  <button className="button secondary" onClick={onRetire}>
                    Retire calendar
                  </button>
                ) : (
                  <p>
                    {current.entry.assignmentRecorded
                      ? "Retirement is frozen by approved assignment history."
                      : "No eligible retirement date remains within validity and sequence limits."}
                  </p>
                )}
              </>
            )}
            {current.entry.draft ? (
              <button className="button secondary" onClick={onAmend}>
                Amend working policy
              </button>
            ) : (
              <p>
                {current.entry.assignmentRecorded
                  ? "Approved assignment history freezes new amendments."
                  : current.entry.retirement
                    ? "Retirement is recorded; new amendments are unavailable."
                    : "No later amendment date is available within the current validity or amendment limit."}
              </p>
            )}
            <p>
              {formatDate(current.entry.currentPolicy.effectiveFrom)} –{" "}
              {formatDate(current.entry.currentPolicy.effectiveUntil)} ·{" "}
              {current.entry.currentPolicy.timeZone}
            </p>
            <p>
              Working weekdays:{" "}
              {current.entry.currentPolicy.workingDays
                .map((d) => weekdays[d - 1])
                .join(", ")}
            </p>
            <h3>Company closures</h3>
            {current.entry.currentPolicy.closedDates.length ? (
              <ul>
                {current.entry.currentPolicy.closedDates.map((d) => (
                  <li key={d}>{formatDate(d)}</li>
                ))}
              </ul>
            ) : (
              <p>No explicit company closures.</p>
            )}
            <form
              className="change-form"
              noValidate
              onSubmit={(e) => {
                e.preventDefault();
                setQuery((q) => ({ date, revision: (q?.revision ?? 0) + 1 }));
              }}
            >
              <label>
                Preview local date
                <input
                  type="date"
                  value={date}
                  onChange={(e) => {
                    setDate(e.target.value);
                    setQuery(null);
                  }}
                />
              </label>
              <button className="button secondary">Preview working date</button>
            </form>
            {query && !answer ? (
              <p role="status">Checking calendar date…</p>
            ) : answer?.error ? (
              <p role="alert" tabIndex={-1} ref={previewError}>
                {answer.error}
              </p>
            ) : (
              answer?.value && (
                <p role="status">
                  {formatDate(answer.value.date)}:{" "}
                  {answer.value.classification === "WORKING_DAY"
                    ? "Working day"
                    : answer.value.classification === "COMPANY_CLOSURE"
                      ? "Company closure"
                      : "Non-working weekday"}{" "}
                  · {answer.value.timeZone} · revision {answer.value.revision},
                  policy effective{" "}
                  {formatDate(answer.value.policyEffectiveFrom)}
                </p>
              )
            )}
            <h3>Amendment history</h3>
            {current.entry.history.length ? (
              current.entry.history.map((e) => (
                <article className="history-event" key={e.revision.version}>
                  <h4>
                    Revision {e.revision.version} · Effective{" "}
                    {formatDate(e.revision.effectiveDate)}
                  </h4>
                  <p>
                    Weekdays:{" "}
                    {e.revision.policy.workingDays
                      .map((d) => weekdays[d - 1])
                      .join(", ")}
                  </p>
                  <p>
                    Company closures:{" "}
                    {e.revision.policy.closedDates.map(formatDate).join(", ") ||
                      "None"}
                  </p>
                  <p>{e.reason}</p>
                  <p>
                    {e.createdBy} · {new Date(e.recordedAt).toLocaleString()} ·
                    Correlation: {e.correlationId}
                  </p>
                </article>
              ))
            ) : (
              <p>No amendments recorded.</p>
            )}
            <h3>Creation audit</h3>
            <p>
              Original weekdays:{" "}
              {current.entry.base.calendar.workingDays
                .map((d) => weekdays[d - 1])
                .join(", ")}
              . Original closures:{" "}
              {current.entry.base.calendar.closedDates
                .map(formatDate)
                .join(", ") || "None"}
              .
            </p>
            <p>
              Recorded by {current.entry.base.createdBy} ·{" "}
              {new Date(current.entry.base.recordedAt).toLocaleString()}
            </p>
            <p>{current.entry.base.reason}</p>
            <p>Correlation: {current.entry.base.correlationId}</p>
            <p>
              Retained company-policy snapshot. No employee assignments,
              statutory holiday rules, leave or payroll changes.
            </p>
          </div>
        )
      )}
    </Modal>
  );
}
