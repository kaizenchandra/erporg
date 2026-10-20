"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { formatDate } from "@/components/ui/primitives";
export type ReferenceHistoryPage = {
  items: {
    id: string;
    code: string;
    actor: string;
    reason: string;
    recordedAt: string;
    effectiveFrom: string;
    correlationId: string;
  }[];
  total: number;
  page: number;
  size: number;
  kind: "DEPARTMENT" | "POSITION";
  throughId: string;
};
export function ReferenceHistory({
  kind,
}: {
  kind: "DEPARTMENT" | "POSITION";
}) {
  const [open, setOpen] = useState(false);
  const name = kind === "DEPARTMENT" ? "Department" : "Position";
  return (
    <details onToggle={(event) => setOpen(event.currentTarget.open)}>
      <summary>{name} configuration history</summary>
      {open && <History kind={kind} name={name} />}
    </details>
  );
}
function History({
  kind,
  name,
}: {
  kind: "DEPARTMENT" | "POSITION";
  name: string;
}) {
  const [draft, setDraft] = useState(""),
    [code, setCode] = useState(""),
    [order, setOrder] = useState("desc"),
    [size, setSize] = useState("10"),
    [page, setPage] = useState(0),
    [boundary, setBoundary] = useState<string | null>(null),
    [retry, setRetry] = useState(0);
  const params = new URLSearchParams({
    kind,
    code,
    order,
    size,
    page: String(page),
  });
  if (boundary !== null) params.set("throughId", boundary);
  const query = params.toString(),
    key = JSON.stringify([query, retry]);
  const [result, setResult] = useState<{
    key: string;
    value?: ReferenceHistoryPage;
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  const heading = useRef<HTMLHeadingElement>(null),
    failure = useRef<HTMLDivElement>(null),
    focus = useRef(false);
  const reset = () => {
    setPage(0);
    setBoundary(null);
    focus.current = true;
    setRetry((n) => n + 1);
  };
  useEffect(() => {
    const controller = new AbortController();
    api<ReferenceHistoryPage>(
      `/api/v1/organisation/reference-history?${query}`,
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
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              (error instanceof Error
                ? error.message
                : "Reference history unavailable.") +
              " Retry or refresh this history to recheck access.",
          });
      });
    return () => controller.abort();
  }, [key, query]);
  useEffect(() => {
    if (current?.error) failure.current?.focus();
    else if (current?.value && focus.current) {
      focus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  return (
    <section
      aria-label={`${name} configuration events`}
      className="reference-history"
    >
      <h4 ref={heading} tabIndex={-1}>
        {name} creation events
      </h4>
      <p>
        Complete authorised creation history. Recording time is UTC; effective
        dates are separate. Refresh includes new events. Closing history clears
        its filters and pages.
      </p>
      <form
        className="change-history-filters"
        onSubmit={(event) => {
          event.preventDefault();
          setCode(draft.trim().toUpperCase());
          reset();
        }}
      >
        <label>
          {name} history reference code
          <input
            maxLength={30}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="All codes, or an exact code"
          />
        </label>
        <button type="submit" className="button secondary">
          Apply {name.toLowerCase()} history code
        </button>
      </form>
      <div className="change-history-filters">
        <label>
          {name} history recording order
          <select
            value={order}
            onChange={(event) => {
              setOrder(event.target.value);
              reset();
            }}
          >
            <option value="desc">Newest first</option>
            <option value="asc">Oldest first</option>
          </select>
        </label>
        <label>
          {name} history rows per page
          <select
            value={size}
            onChange={(event) => {
              setSize(event.target.value);
              reset();
            }}
          >
            <option value="10">10</option>
            <option value="25">25</option>
            <option value="50">50</option>
          </select>
        </label>
      </div>
      {!current ? (
        <p role="status">Loading {name.toLowerCase()} history…</p>
      ) : current.error ? (
        <div ref={failure} role="alert" tabIndex={-1}>
          {current.error}
          <button
            type="button"
            onClick={() => {
              focus.current = true;
              setRetry((n) => n + 1);
            }}
          >
            Retry {name.toLowerCase()} history
          </button>
        </div>
      ) : (
        current.value && (
          <>
            <p role="status">
              Creation events: {current.value.total} · Page {page + 1} ·{" "}
              {order === "desc" ? "Newest first" : "Oldest first"}
            </p>
            {!current.value.items.length && (
              <p>
                No matching creation events. Clear the code filter or refresh to
                include new records.
              </p>
            )}
            {current.value.items.map((item) => (
              <article className="history-event" key={item.id}>
                <div>
                  <strong>{item.code} · Created</strong>
                  <p>{item.reason}</p>
                  <small>
                    {item.actor} · Recorded{" "}
                    <time dateTime={item.recordedAt}>{item.recordedAt}</time> ·
                    Effective {formatDate(item.effectiveFrom)}
                  </small>
                  <details>
                    <summary>Creation trace reference</summary>
                    <p>{item.correlationId}</p>
                  </details>
                </div>
              </article>
            ))}
          </>
        )
      )}
      <div className="change-history-actions">
        <button
          type="button"
          className="button secondary"
          disabled={!current?.value || page === 0}
          onClick={() => {
            focus.current = true;
            setPage((p) => p - 1);
          }}
        >
          Previous {name.toLowerCase()} history
        </button>
        <button
          type="button"
          className="button secondary"
          disabled={
            !current?.value || (page + 1) * Number(size) >= current.value.total
          }
          onClick={() => {
            if (current?.value) {
              focus.current = true;
              setBoundary(current.value.throughId);
              setPage((p) => p + 1);
            }
          }}
        >
          Next {name.toLowerCase()} history
        </button>
        <button
          type="button"
          className="button secondary"
          disabled={!current}
          onClick={reset}
        >
          Refresh {name.toLowerCase()} history
        </button>
      </div>
    </section>
  );
}
