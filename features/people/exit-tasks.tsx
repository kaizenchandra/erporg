"use client";
import { useEffect, useRef, useState } from "react";
import { Badge, Empty, Modal, formatDate } from "@/components/ui/primitives";
import { ManageTask, ManagementHistory } from "./exit-task-management";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import type {
  ExitTask,
  ExitTaskItem,
  ExitTaskCase,
  ExitTaskPage,
  ExitTaskAudit,
} from "./exit-task-contracts";

export function MyExitTasks({ actor }: { actor: Actor }) {
  const [page, setPage] = useState(0),
    [status, setStatus] = useState("ALL"),
    [revision, setRevision] = useState(0);
  const [data, setData] = useState<ExitTaskPage | null>(null),
    [error, setError] = useState(""),
    [selected, setSelected] = useState<ExitTask | null>(null);
  const reload = () => {
    setData(null);
    setError("");
    setRevision(revision + 1);
  };
  useEffect(() => {
    const controller = new AbortController();
    api<ExitTaskPage>(
      `/api/v1/exit-tasks?status=${status}&page=${page}&size=12`,
      { signal: controller.signal },
    )
      .then((r) => {
        if (!controller.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error ? e.message : "Unable to load exit tasks.",
          );
      });
    return () => controller.abort();
  }, [actor.subject, actor.tenant, actor.role, page, status, revision]);
  return (
    <section className="department-reference" aria-label="My exit tasks">
      <div className="eyebrow">ASSIGNED TO YOU</div>
      <h2>My exit tasks</h2>
      <p>
        Only tasks assigned to your employee identity appear here. Assignment
        does not grant access to resignation reasons or HR decisions.
      </p>
      <div className="position-filters">
        <label>
          Exit task status
          <select
            value={status}
            onChange={(e) => {
              setData(null);
              setError("");
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="ALL">All tasks</option>
            <option value="OPEN">Open tasks</option>
            <option value="COMPLETED">Completed tasks</option>
            <option value="CANCELLED">Cancelled tasks</option>
          </select>
        </label>
      </div>
      {error ? (
        <div className="error" role="alert">
          {error}
          <button className="button secondary" onClick={reload}>
            Retry exit tasks
          </button>
        </div>
      ) : !data ? (
        <p role="status">Loading assigned exit tasks…</p>
      ) : (
        <>
          {!data.items.length ? (
            <Empty title="No assigned exit tasks">
              Tasks assigned to you by an HR coordinator will appear here.
            </Empty>
          ) : (
            <div className="detail-grid">
              {data.items.map((item) => (
                <TaskCard
                  key={item.task.id}
                  item={item}
                  onComplete={
                    item.task.status === "OPEN"
                      ? () => setSelected(item.task)
                      : undefined
                  }
                />
              ))}
            </div>
          )}
          <div className="position-pagination">
            <span>
              {data.total} assigned tasks · Page {page + 1}
            </span>
            <button
              className="button secondary"
              disabled={page === 0}
              onClick={() => {
                setPage(page - 1);
                setData(null);
              }}
            >
              Previous exit tasks
            </button>
            <button
              className="button secondary"
              disabled={(page + 1) * data.size >= data.total}
              onClick={() => {
                setPage(page + 1);
                setData(null);
              }}
            >
              Next exit tasks
            </button>
          </div>
        </>
      )}
      {selected && (
        <CompleteTask
          task={selected}
          onClose={() => setSelected(null)}
          onSaved={() => {
            setSelected(null);
            reload();
          }}
        />
      )}
    </section>
  );
}

export function CaseExitTasks({
  requestId,
  canCoordinate,
  coordinationClosed = false,
  onDirty,
  onBusy,
}: {
  requestId: string;
  canCoordinate: boolean;
  coordinationClosed?: boolean;
  onDirty: (dirty: boolean) => void;
  onBusy: (busy: boolean) => void;
}) {
  const [data, setData] = useState<ExitTaskCase | null>(null),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0),
    [creating, setCreating] = useState(false);
  const [managing, setManaging] = useState<ExitTask | null>(null);
  const caseHeading = useRef<HTMLHeadingElement>(null);
  const [managementNotice, setManagementNotice] = useState("");
  const finishManagement = () => {
    const id = managing?.id;
    setManaging(null);
    onDirty(false);
    if (id)
      requestAnimationFrame(() =>
        document.getElementById(`manage-exit-${id}`)?.focus(),
      );
  };
  const reload = () => {
    setData(null);
    setError("");
    setRevision(revision + 1);
  };
  useEffect(() => {
    const controller = new AbortController();
    api<ExitTaskCase>(`/api/v1/resignations/${requestId}/tasks`, {
      signal: controller.signal,
    })
      .then((r) => {
        if (!controller.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error ? e.message : "Unable to load case tasks.",
          );
      });
    return () => controller.abort();
  }, [requestId, revision]);
  return (
    <section className="change-form" aria-label="Case exit tasks">
      <h3 ref={caseHeading} tabIndex={-1}>
        Exit coordination tasks
      </h3>
      {managementNotice && <p role="status">{managementNotice}</p>}
      <p>
        These are assigned manual tasks. Completing them does not terminate
        employment, settle payroll or revoke access. Due dates are explicit
        company scheduling dates.
      </p>
      {!canCoordinate && (
        <p>
          {coordinationClosed
            ? "Employment end is recorded; exit task coordination is closed."
            : "Another HR employee must coordinate your own exit."}
        </p>
      )}
      {error ? (
        <div role="alert" className="error">
          {error}
          <button className="button secondary" onClick={reload}>
            Retry case tasks
          </button>
        </div>
      ) : !data ? (
        <p role="status">Loading case tasks…</p>
      ) : (
        <>
          <p>
            {data.items.length} of 50 tasks ·{" "}
            {data.items.filter((i) => i.task.status === "OPEN").length} open.
            The exit handoff remains pending.
          </p>
          {!data.items.length && (
            <p>No coordination tasks have been assigned.</p>
          )}
          {data.items.map((item) => (
            <TaskCard
              key={item.task.id + "-" + item.task.version}
              item={item}
              showManagement
              onManage={
                canCoordinate &&
                item.task.status === "OPEN" &&
                !creating &&
                !managing
                  ? () => setManaging(item.task)
                  : undefined
              }
            />
          ))}
          {canCoordinate && !creating && !managing && (
            <button
              className="button secondary"
              disabled={data.version >= 50}
              onClick={() => setCreating(true)}
            >
              Assign exit task
            </button>
          )}
          {managing && (
            <ManageTask
              key={managing.id}
              task={managing}
              onDirty={onDirty}
              onBusy={onBusy}
              onClose={finishManagement}
              onSaved={() => {
                setManaging(null);
                onDirty(false);
                setManagementNotice(
                  "Task updated. Review the current assignee and status below.",
                );
                reload();
                caseHeading.current?.focus();
              }}
            />
          )}
          {creating && (
            <CreateTask
              requestId={requestId}
              version={data.version}
              onDirty={onDirty}
              onBusy={onBusy}
              onClose={() => {
                setCreating(false);
                onDirty(false);
              }}
              onSaved={() => {
                setCreating(false);
                onDirty(false);
                reload();
              }}
            />
          )}
        </>
      )}
    </section>
  );
}
function TaskCard({
  item,
  onComplete,
  onManage,
  showManagement = false,
}: {
  item: ExitTaskItem;
  onComplete?: () => void;
  onManage?: () => void;
  showManagement?: boolean;
}) {
  const [history, setHistory] = useState(false);
  const [managementHistory, setManagementHistory] = useState(false);
  const t = item.task;
  return (
    <article className="benefit-card" aria-label={`Exit task ${t.title}`}>
      <h3>{t.title}</h3>
      <Badge status={t.status} />
      {item.overdue && (
        <p>
          <strong>Overdue</strong> · Contact your HR coordinator if you need
          help.
        </p>
      )}
      <dl className="review-details">
        <div>
          <dt>Departing employee</dt>
          <dd>{t.employeeId}</dd>
        </div>
        <div>
          <dt>Assigned employee</dt>
          <dd>{t.assigneeId}</dd>
        </div>
        <div>
          <dt>Due date</dt>
          <dd>{formatDate(t.dueDate)}</dd>
        </div>
        <div>
          <dt>Instructions</dt>
          <dd>{t.instructions}</dd>
        </div>
        <div>
          <dt>Assignment reason</dt>
          <dd>{t.reason}</dd>
        </div>
        {t.completionComment && (
          <div>
            <dt>Completion comment</dt>
            <dd>{t.completionComment}</dd>
          </div>
        )}
      </dl>
      {t.status === "CANCELLED" && (
        <p>
          Cancelled by HR. No completion is required. Contact HR for details.
        </p>
      )}
      {onManage && (
        <button
          type="button"
          id={`manage-exit-${t.id}`}
          className="button secondary"
          onClick={onManage}
        >
          Manage task {t.title}
        </button>
      )}
      {showManagement && (
        <details onToggle={(e) => setManagementHistory(e.currentTarget.open)}>
          <summary>Private management history for {t.title}</summary>
          {managementHistory && <ManagementHistory id={t.id} />}
        </details>
      )}
      {onComplete && (
        <button className="button primary" onClick={onComplete}>
          Complete task {t.title}
        </button>
      )}
      <details onToggle={(e) => setHistory(e.currentTarget.open)}>
        <summary>Task audit for {t.title}</summary>
        {history && <TaskHistory id={t.id} />}
      </details>
    </article>
  );
}
function TaskHistory({ id }: { id: string }) {
  const [data, setData] = useState<ExitTaskAudit[] | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    api<ExitTaskAudit[]>(`/api/v1/exit-tasks/${id}/history`, {
      signal: controller.signal,
    })
      .then((r) => {
        if (!controller.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error ? e.message : "Unable to read task audit.",
          );
      });
    return () => controller.abort();
  }, [id]);
  return error ? (
    <p role="alert">{error} Close and reopen this history to retry.</p>
  ) : !data ? (
    <p role="status">Loading task audit…</p>
  ) : (
    <>
      {data.map((h, i) => (
        <article className="history-event" key={i}>
          <div>
            <strong>
              {h.oldStatus ?? "Unassigned"} → {h.newStatus}
            </strong>
            <p>{h.reason}</p>
            <small>
              {h.actor} · {new Date(h.recordedAt).toLocaleString()} ·{" "}
              {h.correlationId}
            </small>
          </div>
        </article>
      ))}
    </>
  );
}
function ErrorSummary({
  error,
  fields,
  summary,
}: {
  error: string;
  fields: Record<string, string>;
  summary: React.RefObject<HTMLDivElement | null>;
}) {
  return error ? (
    <div className="error" role="alert" tabIndex={-1} ref={summary}>
      <strong>Unable to record exit task</strong>
      <p>{error}</p>
      {Object.entries(fields).map(([field, message]) => (
        <p key={field}>
          <a
            href={`#exit-task-${field}`}
            onClick={() =>
              document.getElementById(`exit-task-${field}`)?.focus()
            }
          >
            {message}
          </a>
        </p>
      ))}
    </div>
  ) : null;
}
function CreateTask({
  requestId,
  version,
  onDirty,
  onBusy,
  onClose,
  onSaved,
}: {
  requestId: string;
  version: number;
  onDirty: (dirty: boolean) => void;
  onBusy: (busy: boolean) => void;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [values, setValues] = useState({
      assigneeId: "",
      title: "",
      instructions: "",
      dueDate: "",
      reason: "",
    }),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null);
  const dirty = Object.values(values).some(Boolean);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  return (
    <form
      noValidate
      onSubmit={async (e) => {
        e.preventDefault();
        if (busy) return;
        setBusy(true);
        onBusy(true);
        setError("");
        setFields({});
        try {
          await api(`/api/v1/resignations/${requestId}/tasks`, {
            method: "POST",
            body: JSON.stringify({
              ...values,
              dueDate: values.dueDate || null,
              version,
              idempotencyKey: key.current,
            }),
          });
          onSaved();
        } catch (e) {
          setError(
            e instanceof Error
              ? `${e.message} Your values are preserved. Refresh the case if its version changed.`
              : "Unable to assign task; retry with the same input.",
          );
          if (e instanceof ApiError) setFields(e.fieldErrors);
        } finally {
          setBusy(false);
          onBusy(false);
        }
      }}
    >
      <h4>Assign a manual exit task</h4>
      <ErrorSummary error={error} fields={fields} summary={summary} />
      {(
        [
          ["assigneeId", "Assigned employee ID"],
          ["title", "Task title"],
          ["instructions", "Task instructions"],
          ["dueDate", "Task due date"],
          ["reason", "Assignment reason"],
        ] as const
      ).map(([field, label]) => (
        <div key={field}>
          <label htmlFor={`exit-task-${field}`}>{label} (required)</label>
          {field === "instructions" || field === "reason" ? (
            <textarea
              id={`exit-task-${field}`}
              required
              maxLength={500}
              value={values[field]}
              aria-invalid={!!fields[field]}
              aria-describedby={
                fields[field] ? `exit-task-${field}-error` : undefined
              }
              onChange={(e) => {
                setValues({ ...values, [field]: e.target.value });
                onDirty(true);
              }}
            />
          ) : (
            <input
              id={`exit-task-${field}`}
              required
              type={field === "dueDate" ? "date" : "text"}
              maxLength={
                field === "title"
                  ? 120
                  : field === "assigneeId"
                    ? 40
                    : undefined
              }
              value={values[field]}
              aria-invalid={!!fields[field]}
              aria-describedby={
                fields[field] ? `exit-task-${field}-error` : undefined
              }
              onChange={(e) => {
                setValues({ ...values, [field]: e.target.value });
                onDirty(true);
              }}
            />
          )}
          {fields[field] && (
            <p className="field-error" id={`exit-task-${field}-error`}>
              {fields[field]}
            </p>
          )}
        </div>
      ))}
      <p>
        Use an employee with a configured workspace identity. Keep operational
        instructions free of credentials and private resignation details.
        Assignment creates one task and a private in-app notification.
      </p>
      <div className="form-footer">
        <button
          type="button"
          className="button secondary"
          disabled={busy}
          onClick={() => {
            if (!dirty || window.confirm("Discard unsaved task assignment?"))
              onClose();
          }}
        >
          Cancel task assignment
        </button>
        <button className="button primary" disabled={busy}>
          {busy ? "Assigning…" : "Confirm task assignment"}
        </button>
      </div>
    </form>
  );
}
function CompleteTask({
  task,
  onClose,
  onSaved,
}: {
  task: ExitTask;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [comment, setComment] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (comment) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [comment]);
  return (
    <Modal
      title="Complete assigned exit task"
      onClose={() => {
        if (
          !busy &&
          (!comment || window.confirm("Discard unsaved completion comment?"))
        )
          onClose();
      }}
    >
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFields({});
          try {
            await api(`/api/v1/exit-tasks/${task.id}/complete`, {
              method: "POST",
              body: JSON.stringify({
                comment,
                version: task.version,
                idempotencyKey: key.current,
              }),
            });
            onSaved();
          } catch (e) {
            setError(
              e instanceof Error
                ? `${e.message} Your comment is preserved; close and refresh if the task changed.`
                : "Unable to record completion. Retry with the same input.",
            );
            if (e instanceof ApiError) setFields(e.fieldErrors);
          } finally {
            setBusy(false);
          }
        }}
      >
        <h3>{task.title}</h3>
        <p>{task.instructions}</p>
        <p>
          This records your manual completion attestation for employee{" "}
          {task.employeeId}. It does not execute payroll, terminate employment
          or remove access.
        </p>
        <ErrorSummary error={error} fields={fields} summary={summary} />
        <label htmlFor="exit-task-comment">Completion comment (required)</label>
        <textarea
          id="exit-task-comment"
          required
          maxLength={500}
          value={comment}
          aria-invalid={!!fields.comment}
          aria-describedby={
            fields.comment ? "exit-task-comment-error" : undefined
          }
          onChange={(e) => setComment(e.target.value)}
        />
        {fields.comment && (
          <p className="field-error" id="exit-task-comment-error">
            {fields.comment}
          </p>
        )}
        <button className="button primary" disabled={busy}>
          {busy ? "Recording…" : "Confirm manual task completion"}
        </button>
      </form>
    </Modal>
  );
}
