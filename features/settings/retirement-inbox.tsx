"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { Badge, formatDate } from "@/components/ui/primitives";
import type { RetirementState } from "./contracts";
import { ReferenceRetirement } from "./reference-retirement";
export type RetirementInboxPage = {
  items: RetirementState[];
  total: number;
  page: number;
  size: number;
};
export function RetirementInbox({ actor }: { actor: Actor }) {
  const [scope, setScope] = useState("REVIEW"),
    [status, setStatus] = useState("PENDING"),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [active, setActive] = useState<string | null>(null);
  const [result, setResult] = useState<{
    key: string;
    data?: RetirementInboxPage;
    error?: string;
  }>();
  const key = `${actor.tenant}:${actor.subject}:${actor.employeeId}:${scope}:${status}:${page}:${revision}`;
  const current = result?.key === key ? result : undefined;
  const heading = useRef<HTMLHeadingElement>(null),
    errorRef = useRef<HTMLParagraphElement>(null),
    focusId = useRef<string | null>(null),
    restore = useRef(false);
  useEffect(() => {
    if (actor.role !== "HR") return;
    const controller = new AbortController();
    api<RetirementInboxPage>(
      `/api/v1/reference-retirement-inbox?scope=${scope}&status=${status}&page=${page}&size=8`,
      {
        signal: AbortSignal.any([
          controller.signal,
          AbortSignal.timeout(10000),
        ]),
      },
    )
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              error instanceof Error
                ? error.message
                : "Retirement queue unavailable. Retry to get current requests.",
          });
      });
    return () => controller.abort();
  }, [actor.role, key, scope, status, page]);
  useEffect(() => {
    if (current?.error) errorRef.current?.focus();
    else if (current?.data && restore.current && !active) {
      restore.current = false;
      const action = focusId.current
        ? document.getElementById(`retirement-inbox-${focusId.current}`)
        : null;
      (action ?? heading.current)?.focus();
    }
  }, [current, active]);
  if (actor.role !== "HR")
    return (
      <p role="alert">
        HR access is required for reference retirement requests.
      </p>
    );
  const reload = () => setRevision((value) => value + 1);
  return (
    <section
      className="additional-contacts contact-inbox"
      aria-label="Reference retirement approval queue"
      aria-busy={!current}
    >
      <h2 tabIndex={-1} ref={heading}>
        Reference retirement requests
      </h2>
      <p>
        Review declared-reference retirement or track your own requests. Each
        decision preserves the original declaration and history; it changes
        catalogue availability on the approved date. It does not change employee
        assignments, payroll or statutory rules.
      </p>
      <label>
        Retirement queue scope
        <select
          value={scope}
          onChange={(e) => {
            setScope(e.target.value);
            setPage(0);
          }}
        >
          <option value="REVIEW">Independent HR review</option>
          <option value="OWN">My requests</option>
        </select>
      </label>
      <label>
        Retirement queue status
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="PENDING">Pending review</option>
          <option value="ALL">All statuses</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
        </select>
      </label>
      {!current ? (
        <p role="status">Loading retirement queue…</p>
      ) : current.error ? (
        <>
          <p
            role="alert"
            aria-label="Retirement queue errors"
            tabIndex={-1}
            ref={errorRef}
          >
            {current.error}
          </p>
          <button className="button secondary" onClick={reload}>
            Retry retirement queue
          </button>
        </>
      ) : (
        current.data && (
          <>
            <p role="status">
              {current.data.total} retirement requests in this scope and status
              · page {page + 1}
            </p>
            {!current.data.items.length && (
              <p>
                {current.data.total
                  ? "No requests remain on this page. Use Previous retirement requests to return to earlier pages."
                  : "No retirement requests match. Change the scope or status to inspect retained history."}
              </p>
            )}
            {current.data.items.map((state) => (
              <article
                className="benefit-card"
                key={state.request.id}
                aria-label={`Retirement request ${state.request.code} attempt ${state.request.ordinal}`}
              >
                <h3>
                  {state.request.code} · attempt {state.request.ordinal}
                </h3>
                <Badge status={state.decision?.decision ?? "SUBMITTED"} />
                <p>
                  Proposed retirement ·{" "}
                  {formatDate(state.request.effectiveDate)}
                </p>
                <p>{state.request.reason}</p>
                <p className="muted">
                  Requested by {state.request.requestedBy} · recorded{" "}
                  {new Date(state.request.recordedAt).toLocaleString()}
                </p>
                {state.decision && (
                  <p>
                    Decision by {state.decision.actor} ·{" "}
                    {state.decision.comment}
                  </p>
                )}
                <button
                  id={`retirement-inbox-${state.request.id}`}
                  className="button secondary"
                  onClick={() => {
                    focusId.current = state.request.id;
                    setActive(state.request.code);
                  }}
                >
                  View retirement · {state.request.code} · attempt{" "}
                  {state.request.ordinal}
                </button>
              </article>
            ))}
            <nav className="pagination" aria-label="Retirement request pages">
              <button
                className="button secondary"
                disabled={page === 0}
                onClick={() => setPage((value) => value - 1)}
              >
                Previous retirement requests
              </button>
              <button
                className="button secondary"
                disabled={(page + 1) * current.data.size >= current.data.total}
                onClick={() => setPage((value) => value + 1)}
              >
                Next retirement requests
              </button>
            </nav>
          </>
        )
      )}
      {active && (
        <ReferenceRetirement
          code={active}
          actor={actor}
          onChanged={reload}
          onClose={() => {
            setActive(null);
            restore.current = true;
            reload();
          }}
        />
      )}
    </section>
  );
}
