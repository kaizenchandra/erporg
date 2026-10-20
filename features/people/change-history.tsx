"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Actor, AuditEvent } from "@/lib/contracts";
import { formatDate } from "@/components/ui/primitives";
import { changeFieldLabels, formatChangeValue } from "./field-labels";
export type ChangeHistoryPage = {
  items: (AuditEvent & { id: string })[];
  total: number;
  size: number;
  order: "asc" | "desc";
  fields: string[];
  nextCursor: string | null;
};
export function ChangeHistory({
  employeeId,
  version,
  actor,
}: {
  employeeId: string;
  version: number;
  actor: Actor;
}) {
  return (
    <History
      key={JSON.stringify([
        employeeId,
        version,
        actor.tenant,
        actor.subject,
        actor.employeeId,
        actor.role,
      ])}
      employeeId={employeeId}
    />
  );
}
function History({ employeeId }: { employeeId: string }) {
  const [field, setField] = useState("ALL"),
    [action, setAction] = useState("ALL"),
    [order, setOrder] = useState("desc"),
    [size, setSize] = useState("10");
  const [cursors, setCursors] = useState<(string | null)[]>([null]);
  const [attempt, setAttempt] = useState(0),
    [fields, setFields] = useState<string[]>([]);
  const [result, setResult] = useState<{
    key: string;
    value?: ChangeHistoryPage;
    error?: string;
  }>();
  const heading = useRef<HTMLHeadingElement>(null),
    error = useRef<HTMLParagraphElement>(null),
    refocus = useRef(false);
  const cursor = cursors[cursors.length - 1];
  const query = new URLSearchParams({ field, action, order, size });
  if (cursor) query.set("cursor", cursor);
  const params = query.toString();
  const key = JSON.stringify([employeeId, params, attempt]);
  const current = result?.key === key ? result : undefined;
  const reset = () => {
    refocus.current = true;
    setCursors([null]);
    setAttempt((n) => n + 1);
  };
  useEffect(() => {
    const c = new AbortController();
    api<ChangeHistoryPage>(
      `/api/v1/employees/${encodeURIComponent(employeeId)}/change-history?${params}`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((value) => {
        if (!c.signal.aborted) {
          setResult({ key, value });
          setFields(value.fields);
        }
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              (e instanceof Error ? e.message : "Change history unavailable.") +
              " Retry, refresh history, or reopen the profile to recheck access.",
          });
      });
    return () => c.abort();
  }, [employeeId, params, key]);
  useEffect(() => {
    if (current?.error) error.current?.focus();
    else if (current?.value && refocus.current) {
      refocus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  const value = (field: string, text: string, removed = false) =>
    formatChangeValue(field, text) ||
    (["positionCode", "calendarCode", "legalEntityCode"].includes(field)
      ? "Unassigned"
      : removed
        ? "Removed"
        : "Not provided");
  return (
    <section aria-label="Employee change history">
      <div className="section-heading">
        <h3 ref={heading} tabIndex={-1}>
          Change history
        </h3>
        <span>Recorded in UTC</span>
      </div>
      <p>
        Only authorised changes are counted and shown. Refresh to include new
        changes; counts may change while others work. Employment-status changes
        are listed separately below.
      </p>
      <div className="change-history-filters">
        <label>
          History field
          <select
            value={field}
            onChange={(e) => {
              setField(e.target.value);
              reset();
            }}
          >
            <option value="ALL">All authorised fields</option>
            {fields.map((f) => (
              <option key={f} value={f}>
                {changeFieldLabels[f] ?? f}
              </option>
            ))}
          </select>
        </label>
        <label>
          History action
          <select
            value={action}
            onChange={(e) => {
              setAction(e.target.value);
              reset();
            }}
          >
            {["ALL", "SUBMITTED", "APPROVED", "REJECTED", "WITHDRAWN"].map(
              (a) => (
                <option key={a} value={a}>
                  {a === "ALL"
                    ? "All actions"
                    : a[0] + a.slice(1).toLowerCase()}
                </option>
              ),
            )}
          </select>
        </label>
        <label>
          History recording order
          <select
            value={order}
            onChange={(e) => {
              setOrder(e.target.value);
              reset();
            }}
          >
            <option value="desc">Newest first</option>
            <option value="asc">Oldest first</option>
          </select>
        </label>
        <label>
          History rows per page
          <select
            value={size}
            onChange={(e) => {
              setSize(e.target.value);
              reset();
            }}
          >
            {[10, 25, 50].map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
      </div>
      {!current ? (
        <p role="status">Loading change history…</p>
      ) : current.error ? (
        <p role="alert" tabIndex={-1} ref={error}>
          {current.error}
          <button
            type="button"
            onClick={() => {
              refocus.current = true;
              setAttempt((n) => n + 1);
            }}
          >
            Retry change history
          </button>
        </p>
      ) : (
        current.value && (
          <>
            <p role="status">
              {current.value.total} authorised changes · Page {cursors.length} ·{" "}
              {order === "desc" ? "Newest first" : "Oldest first"}
            </p>
            {current.value.items.length ? (
              current.value.items.map((h) => (
                <article className="history-event" key={h.id}>
                  <span className="timeline-dot" />
                  <div>
                    <strong>
                      {changeFieldLabels[h.field] ?? h.field} change ·{" "}
                      {h.action.toLowerCase()}
                    </strong>
                    <p>
                      {value(h.field, h.oldValue)} →{" "}
                      {value(h.field, h.newValue, true)}
                    </p>
                    <p>{h.reason}</p>
                    <small>
                      {h.actor} · Recorded{" "}
                      <time dateTime={h.recordedAt}>{h.recordedAt}</time> ·
                      Effective {formatDate(h.effectiveDate)}
                    </small>
                    <details>
                      <summary>Change trace reference</summary>
                      <p>{h.correlationId}</p>
                    </details>
                  </div>
                </article>
              ))
            ) : (
              <p>
                No matching authorised changes. Try different filters; submitted
                requests and decisions appear here when recorded.
              </p>
            )}
          </>
        )
      )}
      <div className="change-history-actions">
        <button
          type="button"
          className="button secondary"
          disabled={!current?.value || cursors.length === 1}
          onClick={() => {
            refocus.current = true;
            setCursors((c) => c.slice(0, -1));
          }}
        >
          Previous change history
        </button>
        <button
          type="button"
          className="button secondary"
          disabled={!current?.value?.nextCursor}
          onClick={() => {
            if (current?.value?.nextCursor) {
              refocus.current = true;
              setCursors((c) => [...c, current.value!.nextCursor]);
            }
          }}
        >
          Next change history
        </button>
        <button
          type="button"
          className="button secondary"
          disabled={!current}
          onClick={reset}
        >
          Refresh change history
        </button>
      </div>
    </section>
  );
}
