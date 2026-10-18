"use client";
import { useEffect, useRef, useState } from "react";
import { Badge, Empty, Modal, formatDate } from "@/components/ui/primitives";
import { ResignationWithdrawalPanel } from "./resignation-withdrawal";
import { CaseEpisodePanel } from "./case-episode";
import { EmploymentEndPanel } from "./employment-end";
import { CaseExitTasks } from "./exit-tasks";
import { api, ApiError } from "@/lib/api";
import type { Actor, Employee } from "@/lib/contracts";
import type {
  Resignation,
  ResignationAudit,
  ResignationPage,
} from "./resignation-contracts";

export function Resignations({
  actor,
  onRefresh,
}: {
  actor: Actor;
  onRefresh: () => void;
}) {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState("ALL");
  const [revision, setRevision] = useState(0);
  const [data, setData] = useState<ResignationPage | null>(null);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [selected, setSelected] = useState<Resignation | "new" | null>(null);
  const reload = () => {
    setData(null);
    setError("");
    setRevision(revision + 1);
  };
  useEffect(() => {
    const controller = new AbortController();
    api<ResignationPage>(
      `/api/v1/resignations?status=${status}&page=${page}&size=12`,
      { signal: controller.signal },
    )
      .then((r) => {
        if (!controller.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error ? e.message : "Unable to load resignations.",
          );
      });
    return () => controller.abort();
  }, [actor.subject, actor.tenant, actor.role, status, page, revision]);
  return (
    <section className="department-reference" aria-label="Resignations">
      <div className="section-heading">
        <div>
          <div className="eyebrow">EMPLOYMENT LIFECYCLE</div>
          <h2>Resignations</h2>
          <p>
            {actor.role === "HR"
              ? "Review tenant requests. Another HR employee must review your own request."
              : "Submit and track your own request. Reasons and decisions are private to you and HR."}
          </p>
        </div>
        <button
          className="button secondary"
          onClick={() => {
            setNotice("");
            setSelected("new");
          }}
        >
          Request my resignation
        </button>
      </div>
      <p>
        Approval accepts the proposed last working date and records pending exit
        coordination. Final pay, exit tasks and access removal are separate,
        unfinished workflows.
      </p>
      {notice && (
        <p role="status" className="success-banner">
          {notice}
        </p>
      )}
      <div className="position-filters">
        <label>
          Resignation status
          <select
            value={status}
            onChange={(e) => {
              setData(null);
              setError("");
              setPage(0);
              setStatus(e.target.value);
            }}
          >
            <option value="ALL">All statuses</option>
            <option value="SUBMITTED">Pending review</option>
            <option value="APPROVED">Approved</option>
            <option value="REJECTED">Rejected</option>
            <option value="WITHDRAWN">Withdrawn</option>
          </select>
        </label>
      </div>
      {error ? (
        <div role="alert" className="error">
          {error}
          <button className="button secondary" onClick={() => reload()}>
            Retry resignations
          </button>
        </div>
      ) : !data ? (
        <p role="status">Loading resignations…</p>
      ) : (
        <>
          {!data.items.length ? (
            <Empty title="No resignation requests">
              Your authorised requests will appear here. You can submit your own
              request above.
            </Empty>
          ) : (
            <div className="detail-grid">
              {data.items.map((r) => (
                <article className="position-card" key={r.id}>
                  <h3>Employee {r.employeeId}</h3>
                  <Badge status={r.status} />
                  <p>
                    Proposed last working date
                    <br />
                    <strong>{formatDate(r.lastWorkingDate)}</strong>
                  </p>
                  <p>
                    {r.status === "APPROVED"
                      ? "Pending exit coordination"
                      : ["REJECTED", "WITHDRAWN"].includes(r.status)
                        ? "You may submit a fresh request."
                        : "Awaiting independent HR review"}
                  </p>
                  <button
                    className="button secondary compact"
                    onClick={() => setSelected(r)}
                  >
                    Review resignation {r.employeeId}
                  </button>
                </article>
              ))}
            </div>
          )}
          <div className="position-pagination">
            <span>
              {data.total} authorised requests · Page {page + 1}
            </span>
            <button
              className="button secondary compact"
              disabled={page === 0}
              onClick={() => {
                setData(null);
                setError("");
                setPage(page - 1);
              }}
            >
              Previous resignations
            </button>
            <button
              className="button secondary compact"
              disabled={(page + 1) * data.size >= data.total}
              onClick={() => {
                setData(null);
                setError("");
                setPage(page + 1);
              }}
            >
              Next resignations
            </button>
          </div>
        </>
      )}
      {selected && (
        <ResignationForm
          key={selected === "new" ? "new" : selected.id}
          actor={actor}
          request={selected === "new" ? null : selected}
          onClose={() => {
            setSelected(null);
            reload();
          }}
          onSaved={() => {
            setSelected(null);
            setNotice("Request recorded. Review the current status below.");
            reload();
            onRefresh();
          }}
        />
      )}
    </section>
  );
}

function ResignationForm({
  actor,
  request,
  onClose,
  onSaved,
}: {
  actor: Actor;
  request: Resignation | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [taskDirty, setTaskDirty] = useState(false);
  const [taskBusy, setTaskBusy] = useState(false);
  const [endDirty, setEndDirty] = useState(false),
    [endBusy, setEndBusy] = useState(false),
    [endRecorded, setEndRecorded] = useState(false);
  const [date, setDate] = useState("");
  const [reason, setReason] = useState("");
  const [decision, setDecision] = useState("APPROVED");
  const [comment, setComment] = useState("");
  const [employee, setEmployee] = useState<Employee | null>(null);
  const [history, setHistory] = useState<ResignationAudit[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const summary = useRef<HTMLDivElement>(null);
  const key = useRef(crypto.randomUUID());
  const dirty = !!(date || reason || comment || taskDirty || endDirty);
  const canDecide =
    !!request &&
    actor.role === "HR" &&
    request.status === "SUBMITTED" &&
    request.requestedBy !== actor.subject &&
    request.employeeId !== actor.employeeId;
  useEffect(() => {
    const controller = new AbortController();
    const options = { signal: controller.signal };
    const read = request
      ? api<ResignationAudit[]>(
          `/api/v1/resignations/${request.id}/history`,
          options,
        ).then((r) => {
          if (!controller.signal.aborted) setHistory(r);
        })
      : api<Employee>(`/api/v1/employees/${actor.employeeId}`, options).then(
          (r) => {
            if (!controller.signal.aborted) setEmployee(r);
          },
        );
    read.catch((e: unknown) => {
      if (!controller.signal.aborted)
        setError(
          e instanceof Error
            ? e.message
            : "Unable to load current context. Close and reopen to retry.",
        );
    });
    return () => controller.abort();
  }, [actor.employeeId, request]);
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
      }
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const close = () => {
    if (
      !busy &&
      !taskBusy &&
      !endBusy &&
      (!dirty || window.confirm("Discard unsaved form changes?"))
    )
      onClose();
  };
  return (
    <Modal
      title={request ? "Review resignation" : "Request my resignation"}
      onClose={close}
      closeDisabled={busy || taskBusy || endBusy}
    >
      <form
        className="change-form"
        noValidate
        onSubmit={async (event) => {
          event.preventDefault();
          if (busy || (request && !canDecide)) return;
          if (!request && !employee) return;
          setBusy(true);
          setError("");
          setFields({});
          try {
            await api(
              request
                ? `/api/v1/resignations/${request.id}/decision`
                : "/api/v1/resignations",
              {
                method: "POST",
                body: JSON.stringify(
                  request
                    ? {
                        decision,
                        comment,
                        version: request.version,
                        idempotencyKey: key.current,
                      }
                    : {
                        lastWorkingDate: date || null,
                        reason,
                        employeeVersion: employee!.version,
                        idempotencyKey: key.current,
                      },
                ),
              },
            );
            onSaved();
          } catch (e) {
            setError(
              e instanceof Error
                ? e.message
                : "Unable to record request. Your values are preserved; retry with the same input.",
            );
            if (e instanceof ApiError) {
              setFields(e.fieldErrors);
              if (e.status === 409)
                setError(
                  `${e.message} Close and reopen for fresh context. Your saved requests remain available.`,
                );
            }
          } finally {
            setBusy(false);
          }
        }}
      >
        {error && (
          <div role="alert" className="error" tabIndex={-1} ref={summary}>
            <strong>Unable to record resignation</strong>
            <p>{error}</p>
            {Object.entries(fields).map(([field, message]) => (
              <p key={field}>
                <a
                  href={`#resignation-${field}`}
                  onClick={() =>
                    document.getElementById(`resignation-${field}`)?.focus()
                  }
                >
                  {message}
                </a>
              </p>
            ))}
          </div>
        )}
        {request ? (
          <>
            <Badge status={request.status} />
            <dl className="review-details">
              <div>
                <dt>Employee</dt>
                <dd>{request.employeeId}</dd>
              </div>
              <div>
                <dt>Proposed last working date</dt>
                <dd>{formatDate(request.lastWorkingDate)}</dd>
              </div>
              <div>
                <dt>Reason</dt>
                <dd>{request.reason}</dd>
              </div>
              {request.comment && (
                <div>
                  <dt>Decision comment</dt>
                  <dd>{request.comment}</dd>
                </div>
              )}
            </dl>
            <CaseEpisodePanel requestId={request.id} />
            {history ? (
              <details>
                <summary>Resignation audit ({history.length})</summary>
                {history.map((h, i) => (
                  <article className="history-event" key={i}>
                    <div>
                      <strong>
                        {h.oldStatus ?? "Draft"} → {h.newStatus}
                      </strong>
                      <p>{h.reason}</p>
                      <small>
                        {h.actor} · {new Date(h.recordedAt).toLocaleString()} ·{" "}
                        {h.correlationId}
                      </small>
                    </div>
                  </article>
                ))}
              </details>
            ) : (
              <p role="status">Loading private history…</p>
            )}
            <ResignationWithdrawalPanel
              request={request}
              actor={actor}
              onDirty={setTaskDirty}
              onBusy={setTaskBusy}
              onSaved={onSaved}
            />
            {canDecide ? (
              <>
                <label htmlFor="resignation-decision">
                  Resignation decision
                  <select
                    id="resignation-decision"
                    value={decision}
                    onChange={(e) => setDecision(e.target.value)}
                  >
                    <option value="APPROVED">Approve resignation</option>
                    <option value="REJECTED">Reject resignation</option>
                  </select>
                </label>
                <label htmlFor="resignation-comment">
                  Decision comment (required)
                </label>
                <textarea
                  id="resignation-comment"
                  required
                  maxLength={500}
                  value={comment}
                  aria-invalid={!!fields.comment}
                  aria-describedby={
                    fields.comment ? "resignation-comment-error" : undefined
                  }
                  onChange={(e) => setComment(e.target.value)}
                />
                {fields.comment && (
                  <p className="field-error" id="resignation-comment-error">
                    {fields.comment}
                  </p>
                )}
                <p>
                  {decision === "APPROVED"
                    ? `Approval accepts ${formatDate(request.lastWorkingDate)} for ${request.employeeId} and creates one pending exit handoff. Employment status and access stay active; payroll settlement and exit tasks are not performed.`
                    : "Rejection creates no exit handoff. The employee may submit a fresh request."}
                </p>
                <button className="button primary" disabled={busy || !history}>
                  {busy ? "Recording…" : "Confirm resignation decision"}
                </button>
              </>
            ) : (
              <p>
                {request.status === "APPROVED"
                  ? endRecorded
                    ? "Employment end recorded. See the local cutoff and pending final-pay/external-identity handoffs below."
                    : "Pending exit coordination. Final pay, exit tasks and access removal have not been executed."
                  : request.status === "SUBMITTED"
                    ? "Awaiting independent HR review. You cannot review your own resignation."
                    : request.status === "WITHDRAWN"
                      ? "Withdrawn before review. Employment and access are unchanged; you may submit a fresh request."
                      : "Rejected. You may submit a fresh request."}
              </p>
            )}
          </>
        ) : (
          <>
            <p>
              Submit your own proposed last working date for independent HR
              review. No statutory notice period is calculated. Draft values are
              kept only while this form is open.
            </p>
            {!employee && !error && (
              <p role="status">Loading your current employee record…</p>
            )}
            <label htmlFor="resignation-lastWorkingDate">
              Proposed last working date (required)
            </label>
            <input
              id="resignation-lastWorkingDate"
              type="date"
              required
              value={date}
              aria-invalid={!!fields.lastWorkingDate}
              aria-describedby={
                fields.lastWorkingDate ? "resignation-date-error" : undefined
              }
              onChange={(e) => setDate(e.target.value)}
            />
            {fields.lastWorkingDate && (
              <p className="field-error" id="resignation-date-error">
                {fields.lastWorkingDate}
              </p>
            )}
            <label htmlFor="resignation-reason">
              Resignation reason (required)
            </label>
            <textarea
              id="resignation-reason"
              required
              maxLength={500}
              value={reason}
              aria-invalid={!!fields.reason}
              aria-describedby={
                fields.reason ? "resignation-reason-error" : undefined
              }
              onChange={(e) => setReason(e.target.value)}
            />
            {fields.reason && (
              <p className="field-error" id="resignation-reason-error">
                {fields.reason}
              </p>
            )}
            <button className="button primary" disabled={busy || !employee}>
              {busy ? "Submitting…" : "Submit resignation request"}
            </button>
          </>
        )}
      </form>
      {request?.status === "APPROVED" && actor.role === "HR" && (
        <EmploymentEndPanel
          actor={actor}
          requestId={request.id}
          employeeId={request.employeeId}
          canConfirm={request.employeeId !== actor.employeeId}
          hasPendingTaskChanges={taskDirty || taskBusy}
          onDirty={setEndDirty}
          onBusy={setEndBusy}
          onRecorded={setEndRecorded}
        />
      )}
      {request?.status === "APPROVED" && actor.role === "HR" && (
        <CaseExitTasks
          requestId={request.id}
          coordinationClosed={endRecorded}
          canCoordinate={
            request.employeeId !== actor.employeeId && !endRecorded
          }
          onDirty={setTaskDirty}
          onBusy={setTaskBusy}
        />
      )}
    </Modal>
  );
}
