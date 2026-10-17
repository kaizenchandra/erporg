"use client";
import { useEffect, useId, useRef, useState } from "react";
import { Modal, Avatar, Badge, formatDate } from "@/components/ui/primitives";
import type { OnboardingCompletion } from "./complete-onboarding";
import { RehirePanel } from "./rehire";
import { ManagerPicker } from "./manager-picker";
import { PositionPicker } from "./position-picker";
import type { Department } from "./department-reference";
import { changeFieldLabels } from "./field-labels";
import { Icon } from "@/components/ui/icon";
import { api, ApiError } from "@/lib/api";
import type { Actor, AuditEvent, Employee } from "@/lib/contracts";
export function Profile({
  employee: initialEmployee,
  actor,
  onClose,
  onChange,
  onRecorded,
}: {
  employee: Employee;
  actor: Actor;
  onClose: () => void;
  onChange: () => void;
  onRecorded: (employee: Employee) => Promise<void>;
}) {
  const [e, setCurrentEmployee] = useState(initialEmployee);
  const [rehireDirty, setRehireDirty] = useState(false),
    [rehireBusy, setRehireBusy] = useState(false);
  const [employmentHistory, setEmploymentHistory] = useState<
    OnboardingCompletion[]
  >([]);
  const [history, setHistory] = useState<AuditEvent[]>([]),
    [error, setError] = useState(""),
    [loading, setLoading] = useState(true);
  useEffect(() => {
    const controller = new AbortController();
    Promise.all([
      api<AuditEvent[]>(`/api/v1/employees/${e.id}/history`, {
        signal: controller.signal,
      }),
      api<OnboardingCompletion[]>(
        `/api/v1/hiring/requests/employees/${e.id}/employment-history`,
        { signal: controller.signal },
      ),
    ])
      .then(([changes, employment]) => {
        if (!controller.signal.aborted) {
          setHistory(changes);
          setEmploymentHistory(employment);
        }
      })
      .catch((err) => {
        if (!controller.signal.aborted) setError(err.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [e.id]);
  return (
    <Modal
      title="Employee profile"
      onClose={() => {
        if (
          !rehireBusy &&
          (!rehireDirty || window.confirm("Discard unsaved rehire values?"))
        )
          onClose();
      }}
      wide
    >
      <div className="profile-cover" />
      <div className="profile-summary">
        <Avatar name={e.name} large />
        <div>
          <h2>{e.name}</h2>
          <p>{e.title}</p>
          <Badge status={e.status} />
        </div>
        <button
          className="button primary"
          onClick={onChange}
          disabled={e.status === "Ended" || rehireDirty || rehireBusy}
        >
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
            [
              "Home address",
              e.address === null ? "Restricted" : e.address || "Not provided",
            ],
            [
              "Emergency contact name",
              e.emergencyContactName === null
                ? "Restricted"
                : e.emergencyContactName || "Not provided",
            ],
            [
              "Emergency contact relationship",
              e.emergencyContactRelationship === null
                ? "Restricted"
                : e.emergencyContactRelationship || "Not provided",
            ],
            [
              "Emergency contact phone",
              e.emergencyContactPhone === null
                ? "Restricted"
                : e.emergencyContactPhone || "Not provided",
            ],
            ["Department", e.department],
            ["Primary position", e.positionCode || "Unassigned"],
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
        {actor.role === "HR" && (
          <RehirePanel
            employeeId={e.id}
            actor={actor}
            onDirty={setRehireDirty}
            onBusy={setRehireBusy}
            onSaved={async () => {
              const updated = await api<Employee>(`/api/v1/employees/${e.id}`);
              setCurrentEmployee(updated);
              await onRecorded(updated);
            }}
          />
        )}
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
                  {changeFieldLabels[h.field] ?? h.field} change ·{" "}
                  {h.action.toLowerCase()}
                </strong>
                <p>
                  {h.oldValue ||
                    (h.field === "positionCode"
                      ? "Unassigned"
                      : "Not provided")}{" "}
                  →{" "}
                  {h.newValue ||
                    (h.field === "positionCode" ? "Unassigned" : "Removed")}
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
        <div className="section-heading">
          <h3>Employment history</h3>
          <span>Effective dates and UTC recording times</span>
        </div>
        {employmentHistory.length ? (
          employmentHistory.map((item) => (
            <article className="history-event" key={item.hireId}>
              <span className="timeline-dot" />
              <div>
                <strong>
                  {item.oldStatus} → {item.newStatus}
                </strong>
                <p>
                  {item.reason ?? "Reason restricted to the employee and HR."}
                </p>
                <small>
                  {item.actor} · Effective {formatDate(item.effectiveDate)} ·
                  Recorded {new Date(item.recordedAt).toISOString()}
                </small>
              </div>
            </article>
          ))
        ) : (
          <p className="muted">No employment-status changes recorded.</p>
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
  const [removeValue, setRemoveValue] = useState(false);
  const removable = [
    "address",
    "emergencyContactName",
    "emergencyContactRelationship",
    "emergencyContactPhone",
    "positionCode",
  ].includes(field);
  const dirty =
    removeValue ||
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
  const [departments, setDepartments] = useState<Department[]>([]);
  const [departmentError, setDepartmentError] = useState("");
  useEffect(() => {
    if (field !== "department") return;
    const controller = new AbortController();
    api<Department[]>("/api/v1/organisation/departments", {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((items) => {
        if (!controller.signal.aborted) {
          setDepartments(items);
          setDepartmentError("");
        }
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setDepartmentError(
            err instanceof Error ? err.message : "Unable to load departments.",
          );
      });
    return () => controller.abort();
  }, [field]);
  const canPrivate = actor.role === "HR" || actor.employeeId === employee.id;
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
                newValue: removeValue ? "" : value || null,
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
            onChange={(e) => {
              setField(e.target.value);
              setRemoveValue(false);
              setValue("");
              setFieldErrors({});
            }}
          >
            <option value="phone">Contact number</option>
            {canPrivate && (
              <>
                <option value="name">Employee display name</option>
                <option value="address">Home address</option>
                <option value="emergencyContactName">
                  Emergency contact name
                </option>
                <option value="emergencyContactRelationship">
                  Emergency contact relationship
                </option>
                <option value="emergencyContactPhone">
                  Emergency contact phone
                </option>
              </>
            )}
            {canEmployment && (
              <>
                <option value="title">Job title / promotion</option>
                <option value="department">Department / transfer</option>
                <option value="location">Work location</option>
                <option value="managerId">
                  Reporting manager / team change
                </option>
                <option value="positionCode">
                  Primary position / assignment
                </option>
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
          Current value{" "}
          <strong>
            {employee[field as keyof Employee] ||
              (field === "positionCode" ? "Unassigned" : "Not provided")}
          </strong>
        </div>
        {removable && (
          <label className="removal-choice">
            <input
              type="checkbox"
              checked={removeValue}
              onChange={(event) => setRemoveValue(event.target.checked)}
            />
            {field === "positionCode"
              ? "Unassign the current position"
              : "Remove the current value"}
          </label>
        )}
        {removeValue && (
          <p className="form-intro">
            This field will become{" "}
            {field === "positionCode" ? "Unassigned" : "Not provided"} after
            approval on the effective date. Its earlier values remain in
            authorised audit history.
          </p>
        )}
        {field === "name" && (
          <p className="form-intro">
            This updates the employee display name after independent HR
            approval. Employee ID, work email and legal identity remain
            separate.
          </p>
        )}
        {field === "managerId" ? (
          <ManagerPicker
            value={value}
            onChange={setValue}
            disabled={false}
            id={`${formId}-newValue`}
            error={fieldErrors.newValue}
          />
        ) : field === "positionCode" ? (
          <PositionPicker
            value={value}
            onChange={setValue}
            disabled={removeValue}
            id={`${formId}-newValue`}
            error={fieldErrors.newValue}
          />
        ) : (
          <label>
            New value {!removeValue && <span>*</span>}
            {field === "department" ? (
              <select
                required
                id={`${formId}-newValue`}
                aria-invalid={!!fieldErrors.newValue}
                aria-describedby={
                  fieldErrors.newValue ? `${formId}-newValue-error` : undefined
                }
                value={value}
                onChange={(e) => setValue(e.target.value)}
              >
                <option value="">Choose a configured department</option>
                {departments.map((d) => (
                  <option key={d.code} value={d.name}>
                    {d.name} · {d.costCentre} · from{" "}
                    {formatDate(d.effectiveFrom)}
                  </option>
                ))}
              </select>
            ) : (
              <input
                required={!removeValue}
                disabled={removeValue}
                maxLength={120}
                id={`${formId}-newValue`}
                aria-invalid={!!fieldErrors.newValue}
                aria-describedby={
                  fieldErrors.newValue ? `${formId}-newValue-error` : undefined
                }
                value={value}
                onChange={(e) => setValue(e.target.value)}
                placeholder={
                  field === "phone" || field === "emergencyContactPhone"
                    ? "+44 7700 900123"
                    : "Enter the proposed value"
                }
              />
            )}
            {field === "department" && departmentError && (
              <span role="alert" className="error">
                {departmentError} Close and reopen this form to retry.
              </span>
            )}
            {fieldErrors.newValue && (
              <span id={`${formId}-newValue-error`} className="error">
                {fieldErrors.newValue}
              </span>
            )}
          </label>
        )}
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
