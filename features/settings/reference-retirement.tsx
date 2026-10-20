"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Badge, Modal, formatDate } from "@/components/ui/primitives";
import type { Actor } from "@/lib/contracts";
import type { RetirementContext, RetirementState } from "./contracts";
export function ReferenceRetirement({
  code,
  actor,
  onClose,
  onChanged,
}: {
  code: string;
  actor: Actor;
  onClose: () => void;
  onChanged: () => void;
}) {
  const path = `/api/v1/legal-entities/${encodeURIComponent(code)}/retirement`;
  const [revision, setRevision] = useState(0),
    [dirty, setDirty] = useState(false),
    [busy, setBusy] = useState(false),
    [notice, setNotice] = useState("");
  const [result, setResult] = useState<{
    key: string;
    data?: RetirementContext;
    error?: string;
  }>();
  const key = `${path}:${revision}`,
    current = result?.key === key ? result : undefined;
  const heading = useRef<HTMLHeadingElement>(null),
    errorRef = useRef<HTMLParagraphElement>(null),
    refocus = useRef(false);
  useEffect(() => {
    const c = new AbortController();
    api<RetirementContext>(path, {
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
                : "Retirement context unavailable.",
          });
      });
    return () => c.abort();
  }, [key, path]);
  useEffect(() => {
    if (current?.error) errorRef.current?.focus();
    else if (current?.data && refocus.current) {
      refocus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  const saved = () => {
    setNotice(
      "Reference decision history updated. Effective retirement is shown below.",
    );
    setDirty(false);
    refocus.current = true;
    setRevision((n) => n + 1);
    onChanged();
  };
  const data = current?.data,
    pending = data?.requests.find((s) => s.decision === null);
  return (
    <Modal
      title="Reference retirement"
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          (!dirty || window.confirm("Discard unsaved retirement details?"))
        )
          onClose();
      }}
    >
      <section
        className="reference-retirement"
        aria-label="Reference retirement context"
      >
        <h3 ref={heading} tabIndex={-1}>
          {code} · retirement
        </h3>
        <p>
          Retirement removes this declared reference from available choices on
          the approved UTC date. The creation record and decision history
          remain. No employee, payment, payroll or statutory record changes.
        </p>
        {notice && <p role="status">{notice}</p>}
        {!current ? (
          <p role="status">Loading retirement context…</p>
        ) : current.error ? (
          <>
            <p
              role="alert"
              aria-label="Retirement context errors"
              tabIndex={-1}
              ref={errorRef}
            >
              {current.error}
            </p>
            <button
              className="button secondary"
              onClick={() => setRevision((n) => n + 1)}
            >
              Retry retirement context
            </button>
          </>
        ) : (
          data && (
            <>
              <h4>{data.reference.entity.name}</h4>
              <details>
                <summary>Original reference and creation audit</summary>
                <p>
                  {data.reference.entity.countryCode} ·{" "}
                  {data.reference.entity.currencyCode} ·{" "}
                  {data.reference.entity.timeZone}
                </p>
                <p>
                  Created by {data.reference.createdBy} ·{" "}
                  {new Date(data.reference.recordedAt).toLocaleString()}
                </p>
                <p>{data.reference.reason}</p>
                <p>Correlation: {data.reference.correlationId}</p>
              </details>

              <p>
                Reference starts{" "}
                {formatDate(data.reference.entity.effectiveFrom)}. Unverified
                declaration.
              </p>
              {data.retirementDate ? (
                <p role="status">
                  {data.retirementDate > new Date().toISOString().slice(0, 10)
                    ? "Retirement scheduled"
                    : "Retired"}{" "}
                  · {formatDate(data.retirementDate)}. No reversal is available.
                </p>
              ) : pending ? (
                <p>Submitted · awaiting independent HR review.</p>
              ) : (
                <p>No approved or pending retirement.</p>
              )}
              <h4>Retirement history</h4>
              {!data.requests.length && (
                <p>No retirement proposals recorded.</p>
              )}
              {data.requests.map((state) => (
                <article
                  className="benefit-card"
                  key={state.request.id}
                  aria-label={`Retirement attempt ${state.request.ordinal}`}
                >
                  <Badge status={state.decision?.decision ?? "SUBMITTED"} />
                  <p>
                    Attempt {state.request.ordinal} · effective{" "}
                    {formatDate(state.request.effectiveDate)}
                  </p>
                  <p>{state.request.reason}</p>
                  <p>
                    Requested by {state.request.requestedBy} ·{" "}
                    {new Date(state.request.recordedAt).toLocaleString()}
                  </p>
                  <p>Correlation: {state.request.correlationId}</p>
                  {state.decision && (
                    <>
                      <p>{state.decision.comment}</p>
                      <p>
                        Reviewed by {state.decision.actor} ·{" "}
                        {new Date(state.decision.recordedAt).toLocaleString()}
                      </p>
                      <p>Correlation: {state.decision.correlationId}</p>
                    </>
                  )}
                </article>
              ))}
              {data.assignmentRecorded && (
                <p role="status">
                  Approved assignment history freezes this declared reference
                  against retirement, including after unassignment.
                </p>
              )}
              {!data.assignmentRecorded &&
                !data.retirementDate &&
                !pending &&
                data.requests.length < 20 && (
                  <RetirementForm
                    path={path}
                    context={data}
                    onDirty={setDirty}
                    onBusy={setBusy}
                    onSaved={saved}
                  />
                )}
              {pending &&
                actor.subject !== pending.request.requestedBy &&
                actor.employeeId !== pending.request.requesterEmployeeId && (
                  <RetirementForm
                    path={path}
                    context={data}
                    reviewing={pending}
                    onDirty={setDirty}
                    onBusy={setBusy}
                    onSaved={saved}
                  />
                )}
              {pending &&
                (actor.subject === pending.request.requestedBy ||
                  actor.employeeId === pending.request.requesterEmployeeId) && (
                  <p>A different HR employee must decide this proposal.</p>
                )}
              <p className="muted">
                Up to twenty attempts. No bulk decisions, withdrawal or reversal
                are available here.
              </p>
            </>
          )
        )}
      </section>
    </Modal>
  );
}
function RetirementForm({
  path,
  context,
  reviewing,
  onDirty,
  onBusy,
  onSaved,
}: {
  path: string;
  context: RetirementContext;
  reviewing?: RetirementState;
  onDirty: (value: boolean) => void;
  onBusy: (value: boolean) => void;
  onSaved: () => void;
}) {
  const [initialDate] = useState(() => new Date().toISOString().slice(0, 10));
  const [date, setDate] = useState(initialDate),
    [text, setText] = useState(""),
    [decision, setDecision] = useState("APPROVED"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [errors, setErrors] = useState<Record<string, string>>({});
  const id = useId(),
    summary = useRef<HTMLParagraphElement>(null),
    attempt = useRef<{ fingerprint: string; key: string } | null>(null);
  const dirty = text !== "" || date !== initialDate || decision !== "APPROVED";
  useEffect(() => {
    onDirty(dirty);
    const f = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [dirty, onDirty]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const field = reviewing ? "comment" : "reason";
  return (
    <form
      className="change-form retirement-form"
      aria-label={
        reviewing ? "Retirement decision form" : "Retirement request form"
      }
      onSubmit={async (e) => {
        e.preventDefault();
        if (busy) return;
        setBusy(true);
        onBusy(true);
        setError("");
        setErrors({});
        const body = reviewing
          ? { decision, comment: text, version: 0 }
          : { effectiveDate: date, reason: text, version: context.version };
        const fingerprint = JSON.stringify(body);
        if (attempt.current?.fingerprint !== fingerprint)
          attempt.current = { fingerprint, key: crypto.randomUUID() };
        try {
          await api(
            reviewing
              ? `${path}/requests/${reviewing.request.id}/decision`
              : `${path}/requests`,
            {
              method: "POST",
              body: JSON.stringify({
                ...body,
                idempotencyKey: attempt.current.key,
              }),
            },
          );
          onSaved();
        } catch (e) {
          if (e instanceof ApiError) setErrors(e.fieldErrors);
          setError(
            (e instanceof Error
              ? e.message
              : "Outcome could not be confirmed") +
              " Entries are preserved. Retry unchanged values or close and refresh reference history.",
          );
        } finally {
          setBusy(false);
          onBusy(false);
        }
      }}
    >
      <h4>
        {reviewing
          ? "Independent retirement decision"
          : "Request reference retirement"}
      </h4>
      {reviewing && (
        <p>
          Decision for attempt {reviewing.request.ordinal}, effective{" "}
          {formatDate(reviewing.request.effectiveDate)}. Approval is terminal; a
          future date does not retire the reference early.
        </p>
      )}
      {error && (
        <p
          role="alert"
          aria-label="Retirement command errors"
          className="error"
          tabIndex={-1}
          ref={summary}
        >
          {error}
        </p>
      )}
      <fieldset disabled={busy}>
        <legend>{reviewing ? "Review details" : "Proposal details"}</legend>
        {reviewing ? (
          <label>
            Retirement decision
            <select
              value={decision}
              onChange={(e) => setDecision(e.target.value)}
            >
              <option value="APPROVED">Approve retirement</option>
              <option value="REJECTED">Reject retirement</option>
            </select>
          </label>
        ) : (
          <label>
            Retirement effective date (required)
            <input
              type="date"
              required
              value={date}
              aria-invalid={!!errors.effectiveDate}
              aria-describedby={errors.effectiveDate ? `${id}-date` : undefined}
              onChange={(e) => setDate(e.target.value)}
            />
            {errors.effectiveDate && (
              <span id={`${id}-date`} className="error">
                {errors.effectiveDate}
              </span>
            )}
          </label>
        )}
        <label>
          {reviewing ? "Retirement review comment" : "Retirement reason"}{" "}
          (required)
          <textarea
            required
            maxLength={500}
            value={text}
            aria-invalid={!!errors[field]}
            aria-describedby={errors[field] ? `${id}-text` : undefined}
            onChange={(e) => setText(e.target.value)}
          />
          {errors[field] && (
            <span id={`${id}-text`} className="error">
              {errors[field]}
            </span>
          )}
        </label>
      </fieldset>
      <button className="button primary" disabled={busy}>
        {busy
          ? "Recording…"
          : reviewing
            ? "Confirm retirement decision"
            : "Submit retirement for review"}
      </button>
    </form>
  );
}
