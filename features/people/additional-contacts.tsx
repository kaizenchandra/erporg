"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { Badge, Modal, formatDate } from "@/components/ui/primitives";
export type Contact = { name: string; relationship: string; phone: string };
export type ContactRequest = {
  id: string;
  employeeId: string;
  oldContacts: Contact[];
  contacts: Contact[];
  effectiveDate: string;
  reason: string;
  status: string;
  requestedBy: string;
  requesterEmployeeId: string;
  employeeVersion: number;
  recordedAt: string;
  correlationId: string;
  version: number;
  decidedBy: string | null;
  comment: string | null;
  decidedAt: string | null;
  decisionCorrelationId: string | null;
};
export type ContactContext = {
  employeeId: string;
  employeeVersion: number;
  contacts: Contact[];
  effectiveDate: string | null;
  requests: {
    items: ContactRequest[];
    total: number;
    page: number;
    size: number;
  };
};
export function ContactList({ contacts }: { contacts: Contact[] }) {
  return contacts.length ? (
    <ul>
      {contacts.map((c, i) => (
        <li key={i}>
          <strong>{c.name}</strong> · {c.relationship}
          <br />
          {c.phone}
        </li>
      ))}
    </ul>
  ) : (
    <p className="muted">No additional contacts recorded.</p>
  );
}
export function AdditionalContacts({
  employeeId,
  actor,
  onRecorded,
}: {
  employeeId: string;
  actor: Actor;
  onRecorded: () => Promise<void>;
}) {
  const [result, setResult] = useState<{
    key: string;
    data?: ContactContext;
    error?: string;
  }>();
  const [revision, setRevision] = useState(0),
    [page, setPage] = useState(0),
    [status, setStatus] = useState("ALL");
  const [editing, setEditing] = useState(false),
    [reviewing, setReviewing] = useState<ContactRequest | null>(null);
  const path = `/api/v1/employees/${encodeURIComponent(employeeId)}/additional-contacts`;
  const summary = useRef<HTMLParagraphElement>(null);
  const requestButton = useRef<HTMLButtonElement>(null);
  const restoreFocus = useRef(false);
  const key = `${path}:${status}:${page}:${revision}`;
  const current = result?.key === key ? result : undefined;
  const data = current?.data,
    error = current?.error ?? "",
    loading = !current;
  useEffect(() => {
    const c = new AbortController();
    api<ContactContext>(`${path}?status=${status}&page=${page}&size=8`, {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!c.signal.aborted) setResult({ key, data });
      })
      .catch((e) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error
                ? e.message
                : "Additional contacts unavailable. Please retry.",
          });
      });
    return () => c.abort();
  }, [key, path, page, status]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    if (data && restoreFocus.current) {
      restoreFocus.current = false;
      requestButton.current?.focus();
    }
  }, [data]);
  const reload = () => setRevision((n) => n + 1);
  return (
    <section
      className="department-reference additional-contacts"
      aria-label="Additional emergency contacts"
    >
      <div className="section-heading">
        <h3>Additional emergency contacts</h3>
        <span>Private · employee & HR</span>
      </div>
      <p>
        The primary contact is maintained above. Up to four additional contacts
        can be recorded here after independent HR approval.
      </p>
      {loading ? (
        <p role="status">Loading additional contacts…</p>
      ) : error ? (
        <>
          <p role="alert" tabIndex={-1} ref={summary}>
            {error}
          </p>
          <button className="button secondary" onClick={reload}>
            Retry additional contacts
          </button>
        </>
      ) : (
        data && (
          <>
            <ContactList contacts={data.contacts} />
            {data.effectiveDate && (
              <p className="muted">
                Effective {formatDate(data.effectiveDate)}
              </p>
            )}
            <button
              className="button secondary"
              ref={requestButton}
              onClick={() => setEditing(true)}
            >
              Request additional contact changes
            </button>
            <h4>Contact request history</h4>
            <label>
              Contact request status
              <select
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setPage(0);
                }}
              >
                <option value="ALL">All requests</option>
                <option value="SUBMITTED">Submitted</option>
                <option value="APPROVED">Approved</option>
                <option value="REJECTED">Rejected</option>
              </select>
            </label>
            {!data.requests.total && (
              <p className="muted">No additional-contact requests yet.</p>
            )}
            {data.requests.items.map((r) => (
              <article
                className="benefit-card"
                aria-label={`Contact request ${r.id}`}
                key={r.id}
              >
                <Badge status={r.status} />
                <p>
                  Effective {formatDate(r.effectiveDate)} · {r.contacts.length}{" "}
                  additional contacts
                </p>
                <p>{r.reason}</p>
                <details>
                  <summary>Contact request details</summary>
                  <h4>Previous snapshot</h4>
                  <ContactList contacts={r.oldContacts} />
                  <h4>Requested snapshot</h4>
                  <ContactList contacts={r.contacts} />
                  <p>
                    Submitted by {r.requestedBy} ·{" "}
                    {new Date(r.recordedAt).toLocaleString()}
                  </p>
                  <p>Correlation: {r.correlationId}</p>
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
                  actor.subject !== r.requestedBy &&
                  actor.employeeId !== r.employeeId &&
                  actor.employeeId !== r.requesterEmployeeId && (
                    <button
                      className="button primary"
                      onClick={() => setReviewing(r)}
                    >
                      Review additional contact request
                    </button>
                  )}
              </article>
            ))}
            <p className="muted">
              {data.requests.total} requests · page {page + 1}
            </p>
            <div className="form-footer">
              <button
                className="button secondary"
                disabled={!page}
                onClick={() => setPage((p) => p - 1)}
              >
                Previous contact requests
              </button>
              <button
                className="button secondary"
                disabled={(page + 1) * 8 >= data.requests.total}
                onClick={() => setPage((p) => p + 1)}
              >
                Next contact requests
              </button>
            </div>
          </>
        )
      )}
      {editing && data && (
        <ContactEditor
          path={path}
          data={data}
          onClose={() => {
            restoreFocus.current = true;
            setEditing(false);
            reload();
          }}
          onSaved={async () => {
            await onRecorded();
            restoreFocus.current = true;
            setEditing(false);
            setPage(0);
            reload();
          }}
        />
      )}
      {reviewing && (
        <ContactReview
          path={path}
          request={reviewing}
          onClose={() => {
            restoreFocus.current = true;
            setReviewing(null);
            reload();
          }}
          onSaved={async () => {
            await onRecorded();
            restoreFocus.current = true;
            setReviewing(null);
            setPage(0);
            reload();
          }}
        />
      )}
    </section>
  );
}
function ContactEditor({
  path,
  data,
  onClose,
  onSaved,
}: {
  path: string;
  data: ContactContext;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [contacts, setContacts] = useState<Contact[]>(
      data.contacts.map((c) => ({ ...c })),
    ),
    [date, setDate] = useState(new Date().toISOString().slice(0, 10)),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const errorRef = useRef<HTMLParagraphElement>(null),
    attempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const dirty =
    reason !== "" ||
    date !== new Date().toISOString().slice(0, 10) ||
    JSON.stringify(contacts) !== JSON.stringify(data.contacts);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [dirty]);
  useEffect(() => {
    if (error) errorRef.current?.focus();
  }, [error]);
  return (
    <Modal
      title="Request additional contact changes"
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          (!dirty ||
            window.confirm("Discard unsaved additional-contact changes?"))
        )
          onClose();
      }}
    >
      <form
        className="change-form additional-contact-form"
        aria-label="Additional contact request"
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFieldErrors({});
          const body = {
              contacts,
              effectiveDate: date,
              reason,
              employeeVersion: data.employeeVersion,
            },
            fingerprint = JSON.stringify(body);
          if (attempt.current?.fingerprint !== fingerprint)
            attempt.current = { fingerprint, key: crypto.randomUUID() };
          try {
            await api<ContactRequest>(path + "/requests", {
              method: "POST",
              body: JSON.stringify({
                ...body,
                idempotencyKey: attempt.current.key,
              }),
            });
            await onSaved();
          } catch (e) {
            if (e instanceof ApiError) setFieldErrors(e.fieldErrors);
            setError(
              (e instanceof Error ? e.message : "Unable to submit") +
                " Entries are preserved. Retry unchanged values to recover a submitted request, or close and refresh its history.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          This replaces only the additional contacts for {data.employeeId}. The
          primary contact stays unchanged. Removing all entries requires HR
          approval and retains prior history.
        </p>
        {error && (
          <p className="error" role="alert" ref={errorRef} tabIndex={-1}>
            {error}
          </p>
        )}
        {fieldErrors.contacts && (
          <p className="error" id="contacts-error">
            {fieldErrors.contacts}
          </p>
        )}
        {contacts.map((c, index) => (
          <fieldset key={index}>
            <legend>Additional contact {index + 1}</legend>
            {(
              [
                ["name", "Contact name"],
                ["relationship", "Relationship"],
                ["phone", "Phone number"],
              ] as const
            ).map(([key, label]) => (
              <label key={key}>
                {label} {index + 1} (required)
                <input
                  required
                  aria-invalid={!!fieldErrors.contacts}
                  aria-describedby={
                    fieldErrors.contacts ? "contacts-error" : undefined
                  }
                  type={key === "phone" ? "tel" : "text"}
                  maxLength={
                    key === "name" ? 120 : key === "relationship" ? 60 : 30
                  }
                  value={c[key]}
                  onChange={(e) =>
                    setContacts((all) =>
                      all.map((row, i) =>
                        i === index ? { ...row, [key]: e.target.value } : row,
                      ),
                    )
                  }
                />
              </label>
            ))}
            <button
              className="button secondary"
              type="button"
              disabled={busy}
              onClick={() =>
                setContacts((all) => all.filter((_, i) => i !== index))
              }
            >
              Remove additional contact {index + 1}
            </button>
          </fieldset>
        ))}
        <button
          className="button secondary"
          type="button"
          disabled={busy || contacts.length >= 4}
          onClick={() =>
            setContacts((all) => [
              ...all,
              { name: "", relationship: "", phone: "" },
            ])
          }
        >
          Add additional contact
        </button>
        <label>
          Contact effective date (required)
          <input
            type="date"
            required
            aria-invalid={!!fieldErrors.effectiveDate}
            aria-describedby={
              fieldErrors.effectiveDate ? "contact-date-error" : undefined
            }
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
          {fieldErrors.effectiveDate && (
            <span className="error" id="contact-date-error">
              {fieldErrors.effectiveDate}
            </span>
          )}
        </label>
        <label>
          Contact change reason (required)
          <textarea
            required
            maxLength={500}
            aria-invalid={!!fieldErrors.reason}
            aria-describedby={
              fieldErrors.reason ? "contact-reason-error" : undefined
            }
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {fieldErrors.reason && (
            <span className="error" id="contact-reason-error">
              {fieldErrors.reason}
            </span>
          )}
        </label>
        <p>
          {contacts.length} additional contacts requested. An independent HR
          reviewer must approve this snapshot.
        </p>
        <button className="button primary" disabled={busy}>
          {busy ? "Submitting…" : "Submit additional contacts for approval"}
        </button>
      </form>
    </Modal>
  );
}
export function ContactReview({
  path,
  request: r,
  onClose,
  onSaved,
}: {
  path: string;
  request: ContactRequest;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [decision, setDecision] = useState("APPROVED"),
    [comment, setComment] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const summary = useRef<HTMLParagraphElement>(null),
    attempt = useRef<{ fingerprint: string; key: string } | null>(null);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (comment || decision !== "APPROVED") e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [comment, decision]);
  return (
    <Modal
      title="Review additional contact request"
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          ((!comment && decision === "APPROVED") ||
            window.confirm("Discard unsaved contact decision?"))
        )
          onClose();
      }}
    >
      <form
        className="change-form additional-contact-form"
        aria-label="Additional contact decision"
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          const body = { decision, comment, version: r.version },
            fingerprint = JSON.stringify(body);
          if (attempt.current?.fingerprint !== fingerprint)
            attempt.current = { fingerprint, key: crypto.randomUUID() };
          try {
            await api(path + `/requests/${r.id}/decision`, {
              method: "POST",
              body: JSON.stringify({
                ...body,
                idempotencyKey: attempt.current.key,
              }),
            });
            await onSaved();
          } catch (e) {
            setError(
              (e instanceof ApiError
                ? e.message
                : "Decision could not be confirmed") +
                " Your comment is preserved. Retry unchanged values or close and refresh history.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          {r.employeeId} · Effective {formatDate(r.effectiveDate)}. Approval
          replaces only additional contacts; the primary contact is unchanged.
        </p>
        <h4>Previous snapshot</h4>
        <ContactList contacts={r.oldContacts} />
        <h4>Requested snapshot</h4>
        <ContactList contacts={r.contacts} />
        <p>Request reason: {r.reason}</p>
        {error && (
          <p className="error" role="alert" tabIndex={-1} ref={summary}>
            {error}
          </p>
        )}
        <label>
          Contact decision
          <select
            value={decision}
            onChange={(e) => setDecision(e.target.value)}
          >
            <option value="APPROVED">Approve</option>
            <option value="REJECTED">Reject</option>
          </select>
        </label>
        <label>
          Contact decision comment (required)
          <textarea
            required
            maxLength={500}
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
        </label>
        <button className="button primary" disabled={busy}>
          {busy ? "Recording…" : "Confirm additional contact decision"}
        </button>
      </form>
    </Modal>
  );
}
