"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api } from "@/lib/api";
import { formatDate } from "@/components/ui/primitives";
import type { EmployeeCalendarHistoryResult } from "./employee-calendar-contracts";

export function EmployeeCalendarHistory(props: {
  employeeId: string;
  version: number;
}) {
  return (
    <HistoryPanel key={`${props.employeeId}:${props.version}`} {...props} />
  );
}
function HistoryPanel({ employeeId }: { employeeId: string }) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState({ page: 0, attempt: 0 });
  const [result, setResult] = useState<{
    key: string;
    value?: EmployeeCalendarHistoryResult;
    error?: string;
  }>();
  const regionId = useId();
  const heading = useRef<HTMLHeadingElement>(null);
  const error = useRef<HTMLParagraphElement>(null);
  const key = JSON.stringify([employeeId, query]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    api<EmployeeCalendarHistoryResult>(
      `/api/v1/employees/${encodeURIComponent(employeeId)}/calendar-history?${new URLSearchParams({ page: String(query.page), size: "8" })}`,
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
              e instanceof Error
                ? e.message
                : "Calendar assignment history is unavailable.",
          });
      });
    return () => controller.abort();
  }, [employeeId, key, open, query.page]);
  useEffect(() => {
    if (!open) return;
    if (current?.error) error.current?.focus();
    else if (current?.value) heading.current?.focus();
  }, [open, current]);
  return (
    <section
      className="department-reference"
      aria-label="Employee calendar history"
    >
      <button
        type="button"
        className="button secondary"
        aria-expanded={open}
        aria-controls={regionId}
        onClick={() => {
          setOpen((v) => !v);
          setQuery((q) => ({ ...q, attempt: q.attempt + 1 }));
        }}
      >
        {open
          ? "Hide calendar assignment history"
          : "Show calendar assignment history"}
      </button>
      <div id={regionId} hidden={!open}>
        <h3 ref={heading} tabIndex={-1}>
          Calendar assignment history
        </h3>
        <p>
          Approved assignments and explicit unassignments, newest employment
          episode and effective date first. Recording time breaks same-date
          ties. Pending and rejected proposals are excluded. Use the date
          preview to confirm the applied reference; history does not calculate
          leave or pay.
        </p>
        {!current && open && (
          <p role="status">Loading calendar assignment history…</p>
        )}
        {current?.error && (
          <p role="alert" ref={error} tabIndex={-1}>
            {current.error} Retry, or reopen the profile to refresh your access.
            <button
              type="button"
              onClick={() =>
                setQuery((q) => ({ ...q, attempt: q.attempt + 1 }))
              }
            >
              Retry calendar history
            </button>
          </p>
        )}
        {current?.value && (
          <>
            <p role="status">
              {current.value.total} approved calendar records · Page{" "}
              {current.value.page + 1} · Reference date{" "}
              {formatDate(current.value.asOf)}
            </p>
            {current.value.total === 0 ? (
              <p>
                No approved calendar assignments are recorded. Ask HR to review
                a company reference; no entitlement is implied.
              </p>
            ) : current.value.items.length === 0 ? (
              <p>
                No records on this page. Return to the previous page or refresh.
              </p>
            ) : (
              <ol
                className="calendar-assignment-history-list"
                aria-label="Approved calendar assignments"
              >
                {current.value.items.map((item) => (
                  <li key={item.versionId}>
                    <p>
                      <strong>
                        {item.calendarCode
                          ? `${item.calendarName} · ${item.calendarCode}`
                          : "Unassigned"}
                      </strong>
                    </p>
                    {item.timeZone && (
                      <p>Calendar time zone: {item.timeZone}</p>
                    )}
                    <p>
                      Effective {formatDate(item.effectiveDate)}
                      {item.effectiveDate > current.value!.asOf &&
                        " · Scheduled"}
                    </p>
                    <p>
                      Employment episode from {formatDate(item.episodeStart)} ·{" "}
                      {item.episodeStart === current.value!.currentEpisodeStart
                        ? "Current employment episode"
                        : item.episodeStart > current.value!.currentEpisodeStart
                          ? "Future employment episode"
                          : "Earlier employment episode"}
                    </p>
                    <p>
                      Recorded{" "}
                      <time dateTime={item.recordedAt}>
                        {item.recordedAt} (UTC)
                      </time>
                    </p>
                  </li>
                ))}
              </ol>
            )}
          </>
        )}
        <div className="calendar-history-actions">
          <button
            type="button"
            className="button secondary"
            disabled={!current?.value || query.page === 0}
            onClick={() =>
              setQuery((q) => ({ page: q.page - 1, attempt: q.attempt + 1 }))
            }
          >
            Previous calendar history
          </button>
          <button
            type="button"
            className="button secondary"
            disabled={
              !current?.value || (query.page + 1) * 8 >= current.value.total
            }
            onClick={() =>
              setQuery((q) => ({ page: q.page + 1, attempt: q.attempt + 1 }))
            }
          >
            Next calendar history
          </button>
          <button
            type="button"
            className="button secondary"
            disabled={!current}
            onClick={() => setQuery((q) => ({ ...q, attempt: q.attempt + 1 }))}
          >
            Refresh calendar history
          </button>
        </div>
      </div>
    </section>
  );
}
