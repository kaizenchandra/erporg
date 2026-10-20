"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { formatDate } from "@/components/ui/primitives";
import type {
  EmployeeCalendarResult,
  CalendarAssignmentReview,
} from "./employee-calendar-contracts";
export function EmployeeCalendarPreview({
  employeeId,
  calendarCode,
  version,
}: {
  employeeId: string;
  calendarCode: string;
  version: number;
}) {
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [query, setQuery] = useState<{
    date: string;
    attempt: number;
    version: number;
  } | null>(null);
  const [result, setResult] = useState<{
    key: string;
    value?: EmployeeCalendarResult;
    error?: string;
  }>();
  const alert = useRef<HTMLParagraphElement>(null);
  const key = JSON.stringify([employeeId, calendarCode, version, query]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    if (!query || query.version !== version) return;
    const controller = new AbortController();
    api<EmployeeCalendarResult>(
      `/api/v1/employees/${encodeURIComponent(employeeId)}/calendar-preview?${new URLSearchParams({ date: query.date })}`,
      {
        signal: AbortSignal.any([
          controller.signal,
          AbortSignal.timeout(10000),
        ]),
      },
    )
      .then((value) => {
        if (!controller.signal.aborted) setResult({ key, value });
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              (e instanceof Error ? e.message : "Calendar preview failed.") +
              " Choose another date or ask HR to review the assignment; unavailable does not mean a day off.",
          });
      });
    return () => controller.abort();
  }, [employeeId, key, query, version]);
  useEffect(() => {
    if (current?.error) alert.current?.focus();
  }, [current?.error]);
  return (
    <section
      className="department-reference"
      aria-label="Employee company calendar"
    >
      <h3>Company calendar preview</h3>
      <p>
        Current reference: {calendarCode || "Unassigned"}. Preview uses the
        approved assignment for the selected employment episode and local date.
        It does not calculate hours, leave entitlement or pay.
      </p>
      <form
        className="change-form"
        onSubmit={(e) => {
          e.preventDefault();
          setQuery((q) => ({ date, attempt: (q?.attempt ?? 0) + 1, version }));
        }}
      >
        <label>
          Employee calendar local date
          <input
            type="date"
            required
            value={date}
            onChange={(e) => {
              setDate(e.target.value);
              setQuery(null);
            }}
          />
        </label>
        <button className="button secondary">Preview employee date</button>
      </form>
      {query && query.version !== version && (
        <p role="status">
          Employee changed. Preview the date again to refresh the calendar
          result.
        </p>
      )}
      {query && query.version === version && !current && (
        <p role="status">Loading employee calendar preview…</p>
      )}
      {current?.error && (
        <p role="alert" tabIndex={-1} ref={alert}>
          {current.error}
          <button
            type="button"
            onClick={() =>
              setQuery((q) => (q ? { ...q, attempt: q.attempt + 1 } : null))
            }
          >
            Retry employee calendar
          </button>
        </p>
      )}
      {current?.value && (
        <div role="status">
          <p>
            {current.value.calendarName} · {current.value.preview.code} ·{" "}
            {current.value.preview.timeZone}
          </p>
          <p>
            {formatDate(current.value.preview.date)} ·{" "}
            {current.value.preview.classification.replaceAll("_", " ")} ·{" "}
            {current.value.preview.working
              ? "Working date"
              : "Non-working date"}
          </p>
          <p>
            Policy revision {current.value.preview.revision}, effective{" "}
            {formatDate(current.value.preview.policyEffectiveFrom)}
          </p>
        </div>
      )}
    </section>
  );
}
export function CalendarAssignmentContext({
  requestId,
  onReady,
}: {
  requestId: string;
  onReady: (id: string) => void;
}) {
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{
    key: string;
    value?: CalendarAssignmentReview;
    error?: string;
  }>();
  const errorRef = useRef<HTMLParagraphElement>(null);
  const key = JSON.stringify([requestId, retry]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    const c = new AbortController();
    onReady("");
    api<CalendarAssignmentReview>(
      `/api/v1/requests/${encodeURIComponent(requestId)}/calendar-context`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((value) => {
        if (!c.signal.aborted) {
          setResult({ key, value });
          onReady(
            value.policyChanged || value.retirementRecorded ? "" : requestId,
          );
        }
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error
                ? e.message
                : "Assignment policy context unavailable.",
          });
      });
    return () => c.abort();
  }, [requestId, key, onReady]);
  useEffect(() => {
    if (current?.error) errorRef.current?.focus();
  }, [current?.error]);
  return (
    <section aria-label="Calendar assignment impact">
      <h3>Calendar assignment impact</h3>
      {!current ? (
        <p role="status">Loading assignment policy context…</p>
      ) : current.error ? (
        <p role="alert" tabIndex={-1} ref={errorRef}>
          {current.error}
          <button type="button" onClick={() => setRetry((n) => n + 1)}>
            Retry assignment context
          </button>
        </p>
      ) : (
        current.value && (
          <>
            <p>
              Captured policy revision:{" "}
              {current.value.expectedPolicyVersion ?? "Unassignment"}. Current
              revision: {current.value.currentPolicyVersion}.
            </p>
            {(current.value.policyChanged ||
              current.value.retirementRecorded) && (
              <p role="status">
                The calendar policy changed or retirement was recorded. Approval
                will be blocked; reject and resubmit for a fresh review.
              </p>
            )}
            {current.value.base && (
              <>
                <p>
                  {current.value.base.name} · {current.value.base.code} ·{" "}
                  {current.value.base.timeZone} ·{" "}
                  {formatDate(current.value.base.effectiveFrom)} –{" "}
                  {formatDate(current.value.base.effectiveUntil)}
                </p>
                <p>
                  Original weekdays: {current.value.base.workingDays.join(", ")}
                  ; closures:{" "}
                  {current.value.base.closedDates.join(", ") || "None"}.
                </p>
                {current.value.history.map((h) => (
                  <p key={h.version}>
                    Revision {h.version} from {formatDate(h.effectiveDate)} ·
                    weekdays {h.policy.workingDays.join(", ")} · closures{" "}
                    {h.policy.closedDates.join(", ") || "None"}
                  </p>
                ))}
              </>
            )}
            <p>
              Approval affects this employee&apos;s reference in the selected
              employment episode. Approved use permanently freezes that
              calendar&apos;s amendments and retirement, including after
              unassignment. No attendance, leave entitlement or payroll action
              occurs.
            </p>
          </>
        )
      )}
    </section>
  );
}
