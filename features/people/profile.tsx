"use client";
import { useEffect, useId, useRef, useState } from "react";
import { Modal, Avatar, Badge, formatDate } from "@/components/ui/primitives";
import type { OnboardingCompletion } from "./complete-onboarding";
import { RehirePanel } from "./rehire";
import { PostalAddressPanel } from "./postal-address";
import { AdditionalContacts } from "./additional-contacts";
import { ManagerPicker } from "./manager-picker";
import { CalendarPicker } from "./calendar-picker";
import { EntityPicker } from "./entity-picker";
import { EmployeeCalendarPreview } from "./employee-calendar";
import { ChangeHistory } from "./change-history";
import { EmployeeCalendarHistory } from "./employee-calendar-history";
import { PositionPicker } from "./position-picker";
import { DepartmentPicker } from "./department-picker";
import { formatChangeValue } from "./field-labels";
import { Icon } from "@/components/ui/icon";
import { api, ApiError } from "@/lib/api";
import type { Actor, Employee } from "@/lib/contracts";
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
  const [employmentRetry, setEmploymentRetry] = useState(0);
  const [employmentResult, setEmploymentResult] = useState<{
    key: string;
    value?: OnboardingCompletion[];
    error?: string;
  }>();
  const employmentKey = JSON.stringify([e.id, e.version, employmentRetry]);
  const employmentCurrent =
    employmentResult?.key === employmentKey ? employmentResult : undefined;
  const employmentHistory = employmentCurrent?.value ?? [];
  useEffect(() => {
    const c = new AbortController();
    api<OnboardingCompletion[]>(
      `/api/v1/hiring/requests/employees/${e.id}/employment-history`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((value) => {
        if (!c.signal.aborted)
          setEmploymentResult({ key: employmentKey, value });
      })
      .catch((err: unknown) => {
        if (!c.signal.aborted)
          setEmploymentResult({
            key: employmentKey,
            error:
              err instanceof Error
                ? err.message
                : "Employment history unavailable.",
          });
      });
    return () => c.abort();
  }, [e.id, employmentKey]);
  return (
    <Modal
      title="Employee profile"
      closeDisabled={rehireBusy}
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
            [
              "Recorded legal name",
              e.legalName === null
                ? "Restricted"
                : e.legalName || "Not recorded",
            ],
            [
              "Recorded birth date",
              e.birthDate === null
                ? "Restricted"
                : e.birthDate
                  ? formatDate(e.birthDate)
                  : "Not recorded",
            ],
            ["Work email", e.email],
            [
              "Personal email",
              e.personalEmail === null
                ? "Restricted"
                : e.personalEmail || "Not provided",
            ],
            ["Contact number", e.phone],
            [
              "Home address",
              e.address === null
                ? "Restricted"
                : formatChangeValue("address", e.address) || "Not provided",
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
            ["Company calendar", e.calendarCode || "Unassigned"],
            [
              "Declared employing entity",
              e.legalEntityCode === null
                ? "Restricted"
                : e.legalEntityCode || "Unassigned",
            ],
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
        <EmployeeCalendarHistory employeeId={e.id} version={e.version} />
        <EmployeeCalendarPreview
          employeeId={e.id}
          calendarCode={e.calendarCode}
          version={e.version}
        />
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
        {(actor.role === "HR" || actor.employeeId === e.id) && (
          <PostalAddressPanel
            employeeId={e.id}
            onRecorded={async () => {
              const next = await api<Employee>(`/api/v1/employees/${e.id}`);
              setCurrentEmployee(next);
              await onRecorded(next);
            }}
          />
        )}
        {(actor.role === "HR" || actor.employeeId === e.id) && (
          <AdditionalContacts
            employeeId={e.id}
            actor={actor}
            onRecorded={async () => {
              const next = await api<Employee>(`/api/v1/employees/${e.id}`);
              setCurrentEmployee(next);
              await onRecorded(next);
            }}
          />
        )}
        <ChangeHistory employeeId={e.id} version={e.version} actor={actor} />
        <div className="section-heading">
          <h3>Employment history</h3>
          <span>Effective dates and UTC recording times</span>
        </div>
        {!employmentCurrent ? (
          <p role="status">Loading employment history…</p>
        ) : employmentCurrent.error ? (
          <p role="alert">
            {employmentCurrent.error}
            <button
              type="button"
              onClick={() => setEmploymentRetry((n) => n + 1)}
            >
              Retry employment history
            </button>
          </p>
        ) : employmentHistory.length ? (
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
    "personalEmail",
    "address",
    "emergencyContactName",
    "emergencyContactRelationship",
    "emergencyContactPhone",
    "positionCode",
    "calendarCode",
    "legalEntityCode",
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
        {field === "legalName" && (
          <p className="form-help">
            Recorded legal names are private to the employee and HR. HR approval
            records the supplied value; it does not verify identity documents.
          </p>
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
                <option value="legalName">Recorded legal name</option>
                <option value="birthDate">Recorded birth date</option>
                <option value="personalEmail">Personal email</option>
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
            {actor.role === "HR" && (
              <>
                <option value="calendarCode">
                  Company calendar assignment
                </option>
                <option value="legalEntityCode">
                  Declared employing entity
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
            {(field === "address"
              ? formatChangeValue(field, employee.address)
              : employee[field as keyof Employee]) ||
              (["positionCode", "calendarCode", "legalEntityCode"].includes(
                field,
              )
                ? "Unassigned"
                : "Not provided")}
          </strong>
        </div>
        {field === "address" && (
          <p className="form-intro">
            This form accepts a free-text replacement. For separate postal
            fields, use the Postal address section in the profile.
          </p>
        )}
        {removable && (
          <label className="removal-choice">
            <input
              type="checkbox"
              checked={removeValue}
              onChange={(event) => setRemoveValue(event.target.checked)}
            />
            {field === "legalEntityCode"
              ? "Unassign the current declared entity"
              : field === "calendarCode"
                ? "Unassign the current calendar"
                : field === "positionCode"
                  ? "Unassign the current position"
                  : "Remove the current value"}
          </label>
        )}
        {removeValue && (
          <p className="form-intro">
            This field will become{" "}
            {["positionCode", "calendarCode", "legalEntityCode"].includes(field)
              ? "Unassigned"
              : "Not provided"}{" "}
            after approval on the effective date. Its earlier values remain in
            authorised audit history.
          </p>
        )}
        {field === "legalEntityCode" && (
          <p className="form-intro">
            Another HR employee must approve this declared reference for this
            employment episode. Only HR and the employee can inspect this field.
            Pending or approved retirement blocks assignment; approved use
            permanently blocks new retirement, even after unassignment. Supplied
            country/currency/time-zone metadata is unverified. No contract,
            payroll, tax, payment or statutory rule is activated.
          </p>
        )}
        {field === "calendarCode" && (
          <p className="form-intro">
            HR proposes a company reference for this employment episode; another
            HR employee must approve. The calendar policy version is captured on
            submission and checked again at approval. Approved use permanently
            freezes amendments and retirement, including after unassignment.
            Preview classification does not activate attendance, leave
            entitlements or payroll.
          </p>
        )}
        {field === "personalEmail" && (
          <p className="form-intro">
            This records supplied private contact data after independent HR
            approval. It does not verify ownership, send email or change your
            work email, sign-in or account recovery. Use an ASCII address of at
            most 120 characters with a multi-label domain.
          </p>
        )}
        {field === "birthDate" && (
          <p className="form-intro">
            This records a supplied birth date after independent HR approval. It
            does not verify identity or determine age eligibility, benefits or
            payroll. Earlier values remain in authorised history; ordinary
            clearing is unavailable.
          </p>
        )}
        {field === "name" && (
          <p className="form-intro">
            This updates the employee display name after independent HR
            approval. Employee ID, work email and legal identity remain
            separate.
          </p>
        )}
        {field === "legalEntityCode" ? (
          <EntityPicker
            value={value}
            onChange={setValue}
            disabled={removeValue || busy}
            id={`${formId}-newValue`}
            error={fieldErrors.newValue}
          />
        ) : field === "calendarCode" ? (
          <CalendarPicker
            value={value}
            onChange={setValue}
            disabled={removeValue || busy}
            id={`${formId}-newValue`}
            error={fieldErrors.newValue}
          />
        ) : field === "managerId" ? (
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
        ) : field === "department" ? (
          <DepartmentPicker
            value={value}
            onChange={setValue}
            label="New value"
            id={`${formId}-newValue`}
            error={fieldErrors.newValue}
            disabled={busy}
          />
        ) : (
          <label>
            New value {!removeValue && <span>*</span>}
            <input
              type={field === "birthDate" ? "date" : "text"}
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
