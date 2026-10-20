"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal, Badge, Empty, formatDate } from "@/components/ui/primitives";
import type { Actor, Employee } from "@/lib/contracts";
import { CompleteOnboardingDialog } from "./complete-onboarding";
import { DepartmentPicker } from "./department-picker";
export type HireRequest = {
  id: string;
  name: string;
  email: string;
  phone: string;
  title: string;
  department: string;
  location: string;
  managerId: string;
  startDate: string;
  reason: string;
  status: string;
  requestedBy: string;
  requesterEmployeeId: string;
  employeeId: string | null;
  recordedAt: string;
  decidedBy: string | null;
  comment: string | null;
};
type HirePage = {
  items: HireRequest[];
  total: number;
  page: number;
  size: number;
};
export function Hiring({
  actor,
  onRefresh,
  onOpen,
}: {
  actor: Actor;
  onRefresh: () => void;
  onOpen: (employee: Employee) => void;
}) {
  const [status, setStatus] = useState("SUBMITTED"),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [creating, setCreating] = useState(false),
    [review, setReview] = useState<HireRequest | null>(null),
    [notice, setNotice] = useState("");
  const [result, setResult] = useState<{
    key: string;
    data?: HirePage;
    error?: string;
  }>();
  const [completion, setCompletion] = useState<HireRequest | null>(null);
  const key = `${status}:${page}:${revision}`;
  useEffect(() => {
    const controller = new AbortController();
    api<HirePage>(
      `/api/v1/hiring/requests?status=${status}&page=${page}&size=12`,
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
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              err instanceof Error
                ? err.message
                : "Unable to load hire requests.",
          });
      });
    return () => controller.abort();
  }, [key, page, status]);
  const current = result?.key === key ? result : undefined;
  const rows = current?.data?.items ?? [],
    total = current?.data?.total ?? 0;
  const saved = (message: string) => {
    setCreating(false);
    setReview(null);
    setCompletion(null);
    setNotice(message);
    setPage(0);
    setRevision((n) => n + 1);
    onRefresh();
  };
  return (
    <section aria-label="Hiring requests">
      <div className="section-heading">
        <h3>Hiring requests</h3>
        <button className="button primary" onClick={() => setCreating(true)}>
          Submit a hire
        </button>
      </div>
      <p className="muted">
        A different HR reviewer must approve the hire before an employee record
        is created.
      </p>
      {notice && <p role="status">{notice}</p>}
      <label>
        Status{" "}
        <select
          aria-label="Hiring status"
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="SUBMITTED">Submitted</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
          <option value="ALL">All requests</option>
        </select>
      </label>
      {!current && <p role="status">Loading hire requests…</p>}
      {current?.error && (
        <div className="error" role="alert">
          {current.error}
          <button onClick={() => setRevision((n) => n + 1)}>
            Retry hiring requests
          </button>
        </div>
      )}
      <div className="inbox-list">
        {rows.map((h) => (
          <article className="request-card" key={h.id}>
            <div className="request-content">
              <strong>{h.name}</strong>
              <Badge status={h.status} />
              <h3>
                {h.title} · {h.department}
              </h3>
              <p>
                Start {formatDate(h.startDate)} · Requested by {h.requestedBy}
              </p>
              {h.employeeId && (
                <>
                  <p>
                    Employee {h.employeeId} · Employee created · Payroll setup
                    pending
                  </p>
                </>
              )}
            </div>
            <button className="button secondary" onClick={() => setReview(h)}>
              Review hire
            </button>
            {h.employeeId && (
              <>
                <button
                  className="button secondary"
                  onClick={() => setCompletion(h)}
                >
                  Complete onboarding
                </button>
                <button
                  className="button secondary"
                  onClick={async () => {
                    try {
                      onOpen(
                        await api<Employee>(
                          `/api/v1/employees/${h.employeeId}`,
                        ),
                      );
                    } catch (err) {
                      setNotice(
                        err instanceof Error
                          ? err.message
                          : "Unable to open employee.",
                      );
                    }
                  }}
                >
                  Open employee
                </button>
              </>
            )}
          </article>
        ))}
      </div>
      {current?.data && rows.length === 0 && (
        <Empty title="No hiring requests">
          Submit a hire or change the status filter.
        </Empty>
      )}
      <footer className="table-footer">
        <span>
          {total} matching hires · Page {page + 1}
        </span>
        <div>
          <button
            className="button secondary"
            disabled={!current || page === 0}
            onClick={() => setPage((p) => p - 1)}
          >
            Previous hires
          </button>
          <button
            className="button secondary"
            disabled={!current || !!current.error || (page + 1) * 12 >= total}
            onClick={() => setPage((p) => p + 1)}
          >
            Next hires
          </button>
        </div>
      </footer>
      {completion?.employeeId && (
        <CompleteOnboardingDialog
          hireId={completion.id}
          employeeId={completion.employeeId}
          onClose={() => setCompletion(null)}
          onSaved={() =>
            saved("Onboarding completed. Employee status is Active.")
          }
        />
      )}
      {creating && (
        <SubmitHireForm
          onClose={() => setCreating(false)}
          onSaved={() => saved("Hire submitted for independent HR review.")}
        />
      )}
      {review && (
        <ReviewHire
          hire={review}
          actor={actor}
          onClose={() => setReview(null)}
          onSaved={() => saved("Hiring decision recorded.")}
        />
      )}
    </section>
  );
}
function SubmitHireForm({
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved: () => void;
}) {
  const [values, setValues] = useState({
    name: "",
    email: "",
    phone: "",
    title: "",
    department: "",
    location: "",
    managerId: "",
    startDate: new Date().toISOString().slice(0, 10),
    reason: "",
  });
  const [error, setError] = useState(""),
    [fieldErrors, setFieldErrors] = useState<Record<string, string>>({}),
    [busy, setBusy] = useState(false),
    [key] = useState(() => crypto.randomUUID());
  const dirty =
    Object.entries(values).some(
      ([field, value]) => field !== "startDate" && !!value,
    ) || values.startDate !== new Date().toISOString().slice(0, 10);
  const close = () => {
    if (!busy && (!dirty || window.confirm("Discard this unsaved hire?")))
      onClose();
  };
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (dirty) event.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  const summary = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const set = (field: keyof typeof values, value: string) =>
    setValues((old) => ({ ...old, [field]: value }));
  const fields: [keyof typeof values, string, string, number][] = [
    ["name", "Employee name", "text", 120],
    ["email", "Work email", "email", 160],
    ["phone", "Contact number", "tel", 30],
    ["title", "Job title", "text", 120],
    ["location", "Work location", "text", 120],
    ["managerId", "Reporting manager ID", "text", 40],
    ["startDate", "Employment start date", "date", 10],
  ];
  return (
    <Modal title="Submit a hire" onClose={close}>
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
            await api("/api/v1/hiring/requests", {
              method: "POST",
              body: JSON.stringify({
                ...values,
                startDate: values.startDate || null,
                idempotencyKey: key,
              }),
            });
            onSaved();
          } catch (err) {
            setError(
              err instanceof Error
                ? err.message
                : "Unable to submit. Retry with your entered values.",
            );
            setFieldErrors(err instanceof ApiError ? err.fieldErrors : {});
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          Approval creates one employee in onboarding. Payroll setup and sign-in
          access require their own provisioning.
        </p>
        {error && (
          <div
            className="error"
            role="alert"
            tabIndex={-1}
            ref={summary}
            aria-label="Hire request errors"
          >
            {error} Your entries are preserved.
            <ul>
              {Object.entries(fieldErrors).map(([field, message]) => (
                <li key={field}>
                  <a
                    href={`#hire-${field}`}
                    onClick={(event) => {
                      event.preventDefault();
                      document.getElementById(`hire-${field}`)?.focus();
                    }}
                  >
                    {message}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {fields.map(([field, label, type, max]) => (
          <label key={field}>
            {label} <span>*</span>
            <input
              required
              id={`hire-${field}`}
              type={type}
              maxLength={max}
              min={
                type === "date"
                  ? new Date().toISOString().slice(0, 10)
                  : undefined
              }
              value={values[field]}
              onChange={(e) => set(field, e.target.value)}
              aria-invalid={!!fieldErrors[field]}
              aria-describedby={
                fieldErrors[field] ? `hire-${field}-error` : undefined
              }
            />
            {fieldErrors[field] && (
              <span className="error" id={`hire-${field}-error`}>
                {fieldErrors[field]}
              </span>
            )}
          </label>
        ))}
        <DepartmentPicker
          label="Department"
          id="hire-department"
          value={values.department}
          onChange={(value) => set("department", value)}
          error={fieldErrors.department}
          disabled={busy}
        />
        <label>
          Hiring reason <span>*</span>
          <textarea
            required
            id="hire-reason"
            aria-describedby={
              fieldErrors.reason ? "hire-reason-error" : undefined
            }
            rows={3}
            maxLength={500}
            value={values.reason}
            onChange={(e) => set("reason", e.target.value)}
            aria-invalid={!!fieldErrors.reason}
          />
          {fieldErrors.reason && (
            <span className="error" id="hire-reason-error">
              {fieldErrors.reason}
            </span>
          )}
        </label>
        <div className="form-footer">
          <button
            className="button secondary"
            type="button"
            disabled={busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "Submitting…" : "Submit for HR review"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
function ReviewHire({
  hire,
  actor,
  onClose,
  onSaved,
}: {
  hire: HireRequest;
  actor: Actor;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [decision, setDecision] = useState("APPROVED"),
    [comment, setComment] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const [history, setHistory] = useState<
    { newStatus: string; actor: string; reason: string; recordedAt: string }[]
  >([]);
  useEffect(() => {
    const controller = new AbortController();
    api<typeof history>(`/api/v1/hiring/requests/${hire.id}/history`, {
      signal: controller.signal,
    })
      .then((items) => {
        if (!controller.signal.aborted) setHistory(items);
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setError(
            err instanceof Error ? err.message : "Unable to read hiring audit.",
          );
      });
    return () => controller.abort();
  }, [hire.id]);
  const canDecide =
    hire.status === "SUBMITTED" &&
    hire.requestedBy !== actor.subject &&
    hire.requesterEmployeeId !== actor.employeeId;
  return (
    <Modal
      title="Review hire"
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <form
        className="change-form"
        onSubmit={async (event) => {
          event.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          try {
            await api(`/api/v1/hiring/requests/${hire.id}/decision`, {
              method: "POST",
              body: JSON.stringify({ decision, comment }),
            });
            onSaved();
          } catch (err) {
            setError(
              err instanceof Error
                ? err.message
                : "Decision failed; review and retry.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <h3>{hire.name}</h3>
        <Badge status={hire.status} />
        <dl className="review-details">
          {[
            ["Work email", hire.email],
            ["Contact number", hire.phone],
            ["Role", hire.title],
            ["Department", hire.department],
            ["Location", hire.location],
            ["Manager", hire.managerId],
            ["Start date", formatDate(hire.startDate)],
            ["Reason", hire.reason],
          ].map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value}</dd>
            </div>
          ))}
        </dl>
        {error && (
          <div role="alert" className="error">
            {error}
          </div>
        )}
        <details>
          <summary>Hiring audit</summary>
          {history.map((h, i) => (
            <article className="history-event" key={i}>
              <div>
                <strong>{h.newStatus}</strong>
                <p>{h.reason}</p>
                <small>
                  {h.actor} · {new Date(h.recordedAt).toISOString()}
                </small>
              </div>
            </article>
          ))}
        </details>
        {canDecide ? (
          <>
            <label>
              Hiring decision
              <select
                aria-label="Hiring decision"
                value={decision}
                onChange={(e) => setDecision(e.target.value)}
              >
                <option value="APPROVED">Approve hire</option>
                <option value="REJECTED">Reject hire</option>
              </select>
            </label>
            <label>
              Hiring decision comment <span>*</span>
              <textarea
                required
                rows={3}
                maxLength={500}
                value={comment}
                onChange={(e) => setComment(e.target.value)}
              />
            </label>
            <p>
              {decision === "APPROVED"
                ? "Approval creates one employee, records the decision and queues dependent setup."
                : "Rejection leaves the employee directory unchanged and notifies the requester."}
            </p>
            <button className="button primary" disabled={busy}>
              {busy
                ? "Recording…"
                : decision === "APPROVED"
                  ? "Confirm hire approval"
                  : "Confirm hire rejection"}
            </button>
          </>
        ) : (
          <p>
            {hire.status === "SUBMITTED"
              ? "Another HR employee must review your hiring request."
              : `This hire is ${hire.status.toLowerCase()}. ${hire.comment ?? ""}`}
          </p>
        )}
      </form>
    </Modal>
  );
}
