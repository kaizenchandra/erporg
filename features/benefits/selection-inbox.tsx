"use client";
import { useEffect, useRef, useState } from "react";
import type { Actor } from "@/lib/contracts";
import { api } from "@/lib/api";
import { Badge, formatDate } from "@/components/ui/primitives";
import {
  BenefitSelectionForm,
  type Selection,
  type SelectionPage,
} from "./selections";

export function BenefitSelectionInbox({
  actor,
  onSaved,
}: {
  actor: Actor;
  onSaved: (message?: string) => void;
}) {
  const [scope, setScope] = useState(actor.role === "HR" ? "REVIEW" : "OWN"),
    [status, setStatus] = useState("PENDING"),
    [size, setSize] = useState("8"),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [active, setActive] = useState<Selection | null>(null);
  const query = new URLSearchParams({
    scope,
    status,
    size,
    page: String(page),
  }).toString();
  const key = JSON.stringify([
    actor.subject,
    actor.tenant,
    actor.employeeId,
    actor.role,
    query,
    revision,
  ]);
  const [result, setResult] = useState<{
    key: string;
    value?: SelectionPage;
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  const heading = useRef<HTMLHeadingElement>(null),
    failure = useRef<HTMLDivElement>(null),
    focus = useRef(false);
  const reload = () => {
    focus.current = true;
    setRevision((n) => n + 1);
  };
  useEffect(() => {
    const controller = new AbortController();
    api<SelectionPage>(`/api/v1/benefits/selection-inbox?${query}`, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
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
                : "Benefit queue unavailable.") +
              " Retry or refresh to recheck access.",
          });
      });
    return () => controller.abort();
  }, [key, query]);
  useEffect(() => {
    if (current?.error) {
      focus.current = false;
      failure.current?.focus();
    } else if (current?.value && focus.current) {
      focus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  return (
    <section
      className="additional-contacts contact-inbox"
      aria-label="Benefit selection approval queue"
      aria-busy={!current}
    >
      <h2 ref={heading} tabIndex={-1}>
        Benefit selection requests
      </h2>
      <p>
        Track your employee record or review as independent HR. Dependant
        details are private. Approval queues a demonstration deduction for later
        payroll; it does not enrol with a provider or make a payment.
      </p>
      <div className="position-filters">
        <label>
          Benefit queue scope
          <select
            value={scope}
            onChange={(e) => {
              setScope(e.target.value);
              setPage(0);
            }}
          >
            {actor.role === "HR" && (
              <option value="REVIEW">Independent HR review</option>
            )}
            <option value="OWN">My employee record</option>
          </select>
        </label>
        <label>
          Benefit queue status
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
            <option value="WITHDRAWN">Withdrawn</option>
          </select>
        </label>
        <label>
          Benefit queue rows per page
          <select
            value={size}
            onChange={(e) => {
              setSize(e.target.value);
              setPage(0);
            }}
          >
            <option value="8">8</option>
            <option value="16">16</option>
            <option value="32">32</option>
          </select>
        </label>
      </div>
      {!current ? (
        <p role="status">Loading benefit queue…</p>
      ) : current.error ? (
        <div ref={failure} role="alert" tabIndex={-1}>
          {current.error}
          <button className="button secondary" onClick={reload}>
            Retry benefit queue
          </button>
        </div>
      ) : (
        current.value && (
          <>
            <p role="status">
              {current.value.total} benefit{" "}
              {current.value.total === 1 ? "request" : "requests"} in this scope
              and status · Page {page + 1}
            </p>
            {!current.value.items.length && (
              <p>
                No benefit requests match. Change the scope or status, or
                refresh to inspect retained decisions.
              </p>
            )}
            {current.value.items.map((item) => (
              <article
                className="benefit-card"
                aria-label={`Benefit inbox request ${item.id}`}
                key={item.id}
              >
                <h3>
                  {item.planName} · {item.employeeId}
                </h3>
                <Badge status={item.status} />
                <p>
                  {item.planCode} · {item.currency} {item.monthlyContribution}{" "}
                  monthly · Effective {formatDate(item.effectiveDate)}
                </p>
                <p>
                  Submitted by {item.requestedBy} · {item.reason}
                </p>
                <p>Payroll handoff: {item.handoffStatus ?? "Not created"}</p>
                <details>
                  <summary>Benefit request context and history</summary>
                  {item.dependants.length ? (
                    item.dependants.map((d, i) => (
                      <p key={i}>
                        {d.name} · {d.relationship}
                      </p>
                    ))
                  ) : (
                    <p>No dependants selected.</p>
                  )}
                  <p>
                    Rule {item.ruleVersion}: demonstration fixed contribution,
                    no dependant surcharge or proration.
                  </p>
                  {item.history.map((event, i) => (
                    <p key={i}>
                      {event.action} · {event.actor} · {event.reason} · Recorded{" "}
                      <time dateTime={event.recordedAt}>
                        {event.recordedAt}
                      </time>{" "}
                      · Correlation: {event.correlationId}
                    </p>
                  ))}
                </details>
                {scope === "REVIEW" &&
                  actor.role === "HR" &&
                  item.status === "PENDING" &&
                  item.employeeId !== actor.employeeId &&
                  item.requestedBy !== actor.subject && (
                    <button
                      className="button primary"
                      onClick={() => setActive(item)}
                    >
                      Review benefit request {item.employeeId} · {item.planCode}
                    </button>
                  )}
              </article>
            ))}
            <div className="position-pagination">
              <button
                className="button secondary"
                disabled={page === 0}
                onClick={() => {
                  focus.current = true;
                  setPage((p) => p - 1);
                }}
              >
                Previous benefit queue page
              </button>
              <button
                className="button secondary"
                disabled={(page + 1) * Number(size) >= current.value.total}
                onClick={() => {
                  focus.current = true;
                  setPage((p) => p + 1);
                }}
              >
                Next benefit queue page
              </button>
            </div>
          </>
        )
      )}
      <button
        className="button secondary"
        disabled={!current}
        onClick={() => {
          setPage(0);
          reload();
        }}
      >
        Refresh benefit queue
      </button>
      {active && (
        <BenefitSelectionForm
          plan={null}
          review={active}
          onClose={() => {
            setActive(null);
            reload();
          }}
          onSaved={() => {
            setActive(null);
            setPage(0);
            reload();
            onSaved(
              "Benefit decision recorded. Approved deductions remain pending payroll; no payment was made.",
            );
          }}
        />
      )}
    </section>
  );
}
