"use client";
import { useEffect, useId, useRef, useState } from "react";
import { Modal, Avatar, Badge, formatDate } from "@/components/ui/primitives";
import { Icon } from "@/components/ui/icon";
import { api, ApiError } from "@/lib/api";
import type { Actor, AuditEvent, Employee } from "@/lib/contracts";
export function Profile({
  employee: e,
  actor,
  onClose,
  onChange,
}: {
  employee: Employee;
  actor: Actor;
  onClose: () => void;
  onChange: () => void;
}) {
  const [history, setHistory] = useState<AuditEvent[]>([]),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    api<AuditEvent[]>(`/api/v1/employees/${e.id}/history`, {
      signal: controller.signal,
    })
      .then(setHistory)
      .catch((err) => {
        if (!controller.signal.aborted) setError(err.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [e.id]);
  return (
    <Modal title="Employee profile" onClose={onClose} wide>
      <div className="profile-cover" />
      <div className="profile-summary">
        <Avatar name={e.name} large />
        <div>
          <h2>{e.name}</h2>
          <p>{e.title}</p>
          <Badge status={e.status} />
        </div>
        <button className="button primary" onClick={onChange}>
          <Icon name="plus" size={17} />
          Request a change
        </button>
      </div>
      <div className="profile-content">
        <div className="section-heading">
          <h3>Personal & employment information</h3>
          <span>{e.id}</span>
        </div>
        <dl className="detail-grid">
          {[
            ["Work email", e.email],
            ["Contact number", e.phone],
            ["Department", e.department],
            ["Location", e.location],
            ["Employment start", formatDate(e.startDate)],
            ["Reporting manager", e.managerId ?? "No reporting manager"],
          ].map(([k, v]) => (
            <div key={k}>
              <dt>{k}</dt>
              <dd>{v}</dd>
            </div>
          ))}
        </dl>
        <div className="section-heading">
          <h3>Change history</h3>
          <span>Recorded in UTC</span>
        </div>
        {loading ? (
          <p className="muted" role="status">
            Loading history…
          </p>
        ) : error ? (
          <p className="error" role="alert">
            {error}
          </p>
        ) : history.length ? (
          history.map((h, i) => (
            <article className="history-event" key={i}>
              <span className="timeline-dot" />
              <div>
                <strong>
                  {h.field} change · {h.action.toLowerCase()}
                </strong>
                <p>
                  {h.oldValue} → {h.newValue}
                </p>
                <p>{h.reason}</p>
                <small>
                  {h.actor} · Recorded {new Date(h.recordedAt).toISOString()} ·
                  Effective {formatDate(h.effectiveDate)}
                </small>
              </div>
            </article>
          ))
        ) : (
          <div className="quiet-note">
            <Icon name="shield" />
            <p>
              No changes recorded yet. Approved changes and their reasons will
              appear here.
            </p>
          </div>
        )}
        <p className="permission-note">
          <Icon name="shield" size={14} />
          Access scoped to your {actor.role.toLowerCase()} permissions.
        </p>
      </div>
    </Modal>
  );
}
export function ChangeForm({
  employee,
  actor,
  onClose,
  onSaved,
}: {
  employee: Employee;
  actor: Actor;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [field, setField] = useState("phone"),
    [value, setValue] = useState(""),
    [date, setDate] = useState(new Date().toISOString().slice(0, 10)),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [key] = useState(() => crypto.randomUUID());
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const summary = useRef<HTMLDivElement>(null);
  const formId = useId();
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const dirty =
    value !== "" ||
    reason !== "" ||
    field !== "phone" ||
    date !== new Date().toISOString().slice(0, 10);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault();
      }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard your unsaved change request?"))
    )
      onClose();
  };
  const canEmployment =
    actor.role === "HR" ||
    (actor.role === "MANAGER" && actor.employeeId !== employee.id);
  return (
    <Modal title="Request an employee change" onClose={close}>
      <form
        className="change-form"
        noValidate
        onSubmit={async (event) => {
          event.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFieldErrors({});
          try {
            await api("/api/v1/requests", {
              method: "POST",
              body: JSON.stringify({
                employeeId: employee.id,
                field,
                newValue: value,
                effectiveDate: date || null,
                reason,
                version: employee.version,
                idempotencyKey: key,
              }),
            });
            onSaved();
          } catch (err) {
            setFieldErrors(err instanceof ApiError ? err.fieldErrors : {});
            setError(
              err instanceof Error
                ? err.message
                : "Unable to submit. Your entries are preserved.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <div className="form-person">
          <Avatar name={employee.name} />
          <div>
            <strong>{employee.name}</strong>
            <small>
              {employee.id} · {employee.department}
            </small>
          </div>
        </div>
        <p className="form-intro">
          An independent reviewer will approve this change before it takes
          effect.
        </p>
        {error && (
          <div
            className="error"
            role="alert"
            tabIndex={-1}
            ref={summary}
            aria-label="Change request errors"
          >
            {error} Your entries are preserved. Correct the fields below and
            submit again.
            {Object.entries(fieldErrors).length > 0 && (
              <ul>
                {Object.entries(fieldErrors).map(([name, message]) => (
                  <li key={name}>
                    <a
                      href={`#${formId}-${name}`}
                      onClick={(event) => {
                        event.preventDefault();
                        document.getElementById(`${formId}-${name}`)?.focus();
                      }}
                    >
                      {message}
                    </a>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        <label>
          Information to change <span>*</span>
          <select
            id={`${formId}-field`}
            aria-invalid={!!fieldErrors.field}
            aria-describedby={
              fieldErrors.field ? `${formId}-field-error` : undefined
            }
            value={field}
            onChange={(e) => setField(e.target.value)}
          >
            <option value="phone">Contact number</option>
            {canEmployment && (
              <>
                <option value="title">Job title / promotion</option>
                <option value="department">Department / transfer</option>
                <option value="location">Work location</option>
              </>
            )}
          </select>
          {fieldErrors.field && (
            <span id={`${formId}-field-error`} className="error">
              {fieldErrors.field}
            </span>
          )}
        </label>
        <div className="current-value">
          Current value <strong>{employee[field as keyof Employee]}</strong>
        </div>
        <label>
          New value <span>*</span>
          <input
            required
            maxLength={120}
            id={`${formId}-newValue`}
            aria-invalid={!!fieldErrors.newValue}
            aria-describedby={
              fieldErrors.newValue ? `${formId}-newValue-error` : undefined
            }
            value={value}
            onChange={(e) => setValue(e.target.value)}
            placeholder={
              field === "phone" ? "+44 7700 900123" : "Enter the proposed value"
            }
          />
          {fieldErrors.newValue && (
            <span id={`${formId}-newValue-error`} className="error">
              {fieldErrors.newValue}
            </span>
          )}
        </label>
        <label>
          Effective date <span>*</span>
          <input
            required
            type="date"
            id={`${formId}-effectiveDate`}
            aria-invalid={!!fieldErrors.effectiveDate}
            aria-describedby={
              fieldErrors.effectiveDate
                ? `${formId}-effectiveDate-error`
                : undefined
            }
            min={new Date().toISOString().slice(0, 10)}
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
          {fieldErrors.effectiveDate && (
            <span id={`${formId}-effectiveDate-error`} className="error">
              {fieldErrors.effectiveDate}
            </span>
          )}
        </label>
        <label>
          Reason for change <span>*</span>
          <textarea
            required
            maxLength={500}
            id={`${formId}-reason`}
            aria-invalid={!!fieldErrors.reason}
            aria-describedby={
              fieldErrors.reason ? `${formId}-reason-error` : undefined
            }
            rows={3}
            placeholder="Give your reviewer a little context…"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {fieldErrors.reason && (
            <span id={`${formId}-reason-error`} className="error">
              {fieldErrors.reason}
            </span>
          )}
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            onClick={close}
            disabled={busy}
          >
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "Submitting…" : "Submit for approval"}
            <Icon name="arrow" size={16} />
          </button>
        </div>
      </form>
    </Modal>
  );
}
