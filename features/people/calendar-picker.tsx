"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { CalendarEntry } from "@/features/settings/calendar-contracts";
import { formatDate } from "@/components/ui/primitives";
type Page = {
  items: CalendarEntry[];
  total: number;
  page: number;
  size: number;
};
export function CalendarPicker({
  value,
  onChange,
  disabled,
  id,
  error,
}: {
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
  id: string;
  error?: string;
}) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  const query = new URLSearchParams({
    search,
    page: String(page),
    size: "8",
  }).toString();
  const key = JSON.stringify([query, retry]);
  const [result, setResult] = useState<{
    key: string;
    data?: Page;
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    const controller = new AbortController();
    api<Page>(`/api/v1/company-calendars?${query}`, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              err instanceof Error ? err.message : "Unable to load calendars.",
          });
      });
    return () => controller.abort();
  }, [query, key]);
  return (
    <fieldset className="calendar-picker">
      <legend>Company calendar</legend>
      <label>
        Find a calendar
        <input
          maxLength={120}
          disabled={disabled}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
          placeholder="Name or code"
        />
      </label>
      <label>
        New value {!disabled && <span>*</span>}
        <select
          id={id}
          disabled={disabled}
          required={!disabled}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={!!error}
          aria-describedby={error ? `${id}-error` : undefined}
        >
          <option value="">Choose a company calendar</option>
          {value &&
            !current?.data?.items.some((p) => p.calendar.code === value) && (
              <option value={value}>{value} · selected</option>
            )}
          {current?.data?.items.map((p) => (
            <option key={p.calendar.code} value={p.calendar.code}>
              {p.calendar.name} · {p.calendar.code} · {p.calendar.timeZone} ·
              from {formatDate(p.calendar.effectiveFrom)}
            </option>
          ))}
        </select>
        {error && (
          <span className="error" id={`${id}-error`}>
            {error}
          </span>
        )}
      </label>
      {!current ? (
        <p role="status">Loading calendar choices…</p>
      ) : current.error ? (
        <p className="error" role="alert">
          {current.error}{" "}
          <button type="button" onClick={() => setRetry((n) => n + 1)}>
            Retry calendar choices
          </button>
        </p>
      ) : (
        <>
          <p>
            {current.data?.total ?? 0} matching calendars · Page {page + 1}
          </p>
          <div className="calendar-pagination">
            <button
              type="button"
              className="button secondary"
              disabled={disabled || page === 0}
              onClick={() => setPage((n) => n - 1)}
            >
              Previous calendar choices
            </button>
            <button
              type="button"
              className="button secondary"
              disabled={
                disabled || (page + 1) * 8 >= (current.data?.total ?? 0)
              }
              onClick={() => setPage((n) => n + 1)}
            >
              Next calendar choices
            </button>
          </div>
        </>
      )}
      <p className="muted">
        Assignment must fall within calendar validity. Retired references cannot
        be assigned. Approval rechecks the captured policy version; no
        attendance, leave or pay action occurs.
      </p>
    </fieldset>
  );
}
