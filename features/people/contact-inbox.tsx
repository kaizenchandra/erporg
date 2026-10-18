"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { Badge, formatDate } from "@/components/ui/primitives";
import {
  ContactList,
  ContactReview,
  type ContactRequest,
} from "./additional-contacts";
export type ContactPage = {
  items: ContactRequest[];
  total: number;
  page: number;
  size: number;
};
export function ContactInbox({
  actor,
  onSaved,
}: {
  actor: Actor;
  onSaved: (message?: string) => void;
}) {
  const [scope, setScope] = useState(actor.role === "HR" ? "REVIEW" : "OWN"),
    [status, setStatus] = useState("SUBMITTED"),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0);
  const [result, setResult] = useState<{
    key: string;
    data?: ContactPage;
    error?: string;
  }>();
  const [active, setActive] = useState<ContactRequest | null>(null);
  const heading = useRef<HTMLHeadingElement>(null),
    errorRef = useRef<HTMLParagraphElement>(null),
    restoreFocus = useRef(false);
  const key = `${actor.subject}:${actor.tenant}:${scope}:${status}:${page}:${revision}`;
  const current = result?.key === key ? result : undefined;
  const reload = () => setRevision((n) => n + 1);
  useEffect(() => {
    const controller = new AbortController();
    api<ContactPage>(
      `/api/v1/additional-contact-inbox?scope=${scope}&status=${status}&page=${page}&size=8`,
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
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error
                ? e.message
                : "Contact queue unavailable. Please retry.",
          });
      });
    return () => controller.abort();
  }, [key, scope, status, page]);
  useEffect(() => {
    if (current?.error) errorRef.current?.focus();
    else if (current?.data && restoreFocus.current) {
      restoreFocus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  const close = () => {
    restoreFocus.current = true;
    setActive(null);
    reload();
  };
  return (
    <section
      className="additional-contacts contact-inbox"
      aria-label="Emergency contact approval queue"
      aria-busy={!current}
    >
      <h2 ref={heading} tabIndex={-1}>
        Emergency contact requests
      </h2>
      <p>
        Review private contact snapshots or track requests for your employee
        record. Only independent HR can decide. Each decision affects the
        additional list; the primary contact stays unchanged.
      </p>
      <label>
        Contact queue scope
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
        Contact queue status
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="SUBMITTED">Pending review</option>
          <option value="ALL">All statuses</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
        </select>
      </label>
      {!current ? (
        <p role="status">Loading contact queue…</p>
      ) : current.error ? (
        <>
          <p role="alert" tabIndex={-1} ref={errorRef}>
            {current.error}
          </p>
          <button className="button secondary" onClick={reload}>
            Retry contact queue
          </button>
        </>
      ) : (
        current.data && (
          <>
            <p role="status">
              {current.data.total} contact requests in this scope and status ·
              page {page + 1}
            </p>
            {!current.data.total && (
              <p>
                No contact requests match. Change the scope or status to inspect
                retained history.
              </p>
            )}
            {current.data.items.map((r) => (
              <article
                className="benefit-card"
                aria-label={`Contact queue request ${r.id}`}
                key={r.id}
              >
                <h3>Additional contacts · {r.employeeId}</h3>
                <Badge status={r.status} />
                <p>
                  Effective {formatDate(r.effectiveDate)} · Submitted by{" "}
                  {r.requestedBy}
                </p>
                <p>{r.reason}</p>
                <details>
                  <summary>Inspect contact snapshots and history</summary>
                  <h4>Previous snapshot</h4>
                  <ContactList contacts={r.oldContacts} />
                  <h4>Requested snapshot</h4>
                  <ContactList contacts={r.contacts} />
                  <p>
                    Recorded {new Date(r.recordedAt).toLocaleString()} ·
                    Correlation: {r.correlationId}
                  </p>
                  {r.decidedAt && (
                    <>
                      <p>
                        {r.status} by {r.decidedBy} ·{" "}
                        {new Date(r.decidedAt).toLocaleString()}
                      </p>
                      <p>{r.comment}</p>
                      <p>Correlation: {r.decisionCorrelationId}</p>
                    </>
                  )}
                </details>
                {r.status === "SUBMITTED" &&
                  actor.role === "HR" &&
                  actor.employeeId !== r.employeeId &&
                  actor.employeeId !== r.requesterEmployeeId &&
                  actor.subject !== r.requestedBy && (
                    <button
                      className="button primary"
                      onClick={() => setActive(r)}
                    >
                      Review contact request for {r.employeeId}
                    </button>
                  )}
              </article>
            ))}
            <div className="form-footer">
              <button
                className="button secondary"
                disabled={!page}
                onClick={() => setPage((p) => p - 1)}
              >
                Previous contact queue page
              </button>
              <button
                className="button secondary"
                disabled={(page + 1) * 8 >= current.data.total}
                onClick={() => setPage((p) => p + 1)}
              >
                Next contact queue page
              </button>
            </div>
          </>
        )
      )}
      {active && (
        <ContactReview
          path={`/api/v1/employees/${encodeURIComponent(active.employeeId)}/additional-contacts`}
          request={active}
          onClose={close}
          onSaved={async () => {
            restoreFocus.current = true;
            setActive(null);
            setPage(0);
            reload();
            onSaved(
              "Contact decision recorded. The employee has been notified.",
            );
          }}
        />
      )}
    </section>
  );
}
