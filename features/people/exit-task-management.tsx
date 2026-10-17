"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { ExitTask, ExitTaskManagementEvent } from "./exit-task-contracts";

export function ManagementHistory({ id }: { id: string }) {
  const [data, setData] = useState<ExitTaskManagementEvent[] | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    api<ExitTaskManagementEvent[]>(
      `/api/v1/exit-tasks/${id}/management-history`,
      { signal: controller.signal },
    )
      .then((r) => {
        if (!controller.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setError(
            e instanceof Error
              ? e.message
              : "Unable to load management history.",
          );
      });
    return () => controller.abort();
  }, [id]);
  return error ? (
    <p role="alert">{error} Close and reopen this history to retry.</p>
  ) : !data ? (
    <p role="status">Loading private management history…</p>
  ) : !data.length ? (
    <p>No reassignment or cancellation has been recorded.</p>
  ) : (
    <>
      {data.map((e) => (
        <article className="history-event" key={e.ordinal}>
          <div>
            <strong>
              {e.ordinal} · {e.action} · {e.oldStatus} → {e.newStatus}
            </strong>
            <p>
              Assigned employee {e.oldAssigneeId} → {e.newAssigneeId}
            </p>
            <p>{e.reason}</p>
            <small>
              {e.actor} · {new Date(e.recordedAt).toLocaleString()} ·{" "}
              {e.correlationId}
            </small>
          </div>
        </article>
      ))}
    </>
  );
}
export function ManageTask({
  task,
  onDirty,
  onBusy,
  onClose,
  onSaved,
}: {
  task: ExitTask;
  onDirty: (dirty: boolean) => void;
  onBusy: (busy: boolean) => void;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [action, setAction] = useState("REASSIGN"),
    [assigneeId, setAssigneeId] = useState(""),
    [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const key = useRef(crypto.randomUUID()),
    summary = useRef<HTMLDivElement>(null),
    heading = useRef<HTMLHeadingElement>(null);
  const dirty = !!(assigneeId || reason || action !== "REASSIGN");
  useEffect(() => {
    heading.current?.focus();
  }, []);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty || busy) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty, busy]);
  return (
    <form
      noValidate
      aria-label="Manage exit task"
      onSubmit={async (e) => {
        e.preventDefault();
        if (busy) return;
        setBusy(true);
        onBusy(true);
        setError("");
        setFields({});
        try {
          await api(`/api/v1/exit-tasks/${task.id}/management`, {
            method: "POST",
            body: JSON.stringify({
              action,
              assigneeId: action === "REASSIGN" ? assigneeId : null,
              reason,
              version: task.version,
              idempotencyKey: key.current,
            }),
          });
          onSaved();
        } catch (e) {
          setError(
            e instanceof Error
              ? `${e.message} Your values are preserved. Refresh the case if the task changed.`
              : "Unable to manage task. Retry the same input.",
          );
          if (e instanceof ApiError) setFields(e.fieldErrors);
        } finally {
          setBusy(false);
          onBusy(false);
        }
      }}
    >
      <h4 ref={heading} tabIndex={-1}>
        Manage {task.title}
      </h4>
      <p>
        Current assigned employee: {task.assigneeId} · Current task version:{" "}
        {task.version}
      </p>
      {error && (
        <div className="error" role="alert" tabIndex={-1} ref={summary}>
          <strong>Unable to manage exit task</strong>
          <p>{error}</p>
          {Object.entries(fields).map(([field, message]) => (
            <p key={field}>
              <a
                href={`#exit-management-${field}`}
                onClick={() =>
                  document.getElementById(`exit-management-${field}`)?.focus()
                }
              >
                {message}
              </a>
            </p>
          ))}
        </div>
      )}
      <label htmlFor="exit-management-action">Task management action</label>
      <select
        id="exit-management-action"
        value={action}
        onChange={(e) => {
          setFields({});
          setError("");
          setAction(e.target.value);
          onDirty(true);
        }}
      >
        <option value="REASSIGN">Reassign task</option>
        <option value="CANCEL">Cancel task</option>
      </select>
      {action === "REASSIGN" && (
        <>
          <label htmlFor="exit-management-assigneeId">
            New assigned employee ID (required)
          </label>
          <input
            id="exit-management-assigneeId"
            required
            maxLength={40}
            value={assigneeId}
            aria-invalid={!!fields.assigneeId}
            aria-describedby={
              fields.assigneeId ? "exit-management-assigneeId-error" : undefined
            }
            onChange={(e) => {
              setAssigneeId(e.target.value);
              onDirty(true);
            }}
          />
          {fields.assigneeId && (
            <p className="field-error" id="exit-management-assigneeId-error">
              {fields.assigneeId}
            </p>
          )}
        </>
      )}
      <label htmlFor="exit-management-reason">
        Task management reason (required)
      </label>
      <textarea
        id="exit-management-reason"
        required
        maxLength={500}
        value={reason}
        aria-invalid={!!fields.reason}
        aria-describedby={
          fields.reason ? "exit-management-reason-error" : undefined
        }
        onChange={(e) => {
          setReason(e.target.value);
          onDirty(true);
        }}
      />
      {fields.reason && (
        <p className="field-error" id="exit-management-reason-error">
          {fields.reason}
        </p>
      )}
      <p>
        {action === "REASSIGN"
          ? `Reassignment removes future ordinary-role reads and completion access from ${task.assigneeId}, and grants task access to the new assignee. Original assignment and HR-only management history remain retained.`
          : `Cancellation stops completion of this task, notifies assigned employee ${task.assigneeId} and retains its history. It cannot be reopened.`}{" "}
        Neither action terminates employment, settles payroll or removes system
        access.
      </p>
      <div className="form-footer">
        <button
          type="button"
          className="button secondary"
          disabled={busy}
          onClick={() => {
            if (
              !dirty ||
              window.confirm("Discard unsaved task management changes?")
            )
              onClose();
          }}
        >
          Close task management
        </button>
        <button className="button primary" disabled={busy}>
          {busy
            ? "Recording…"
            : action === "REASSIGN"
              ? "Confirm task reassignment"
              : "Confirm task cancellation"}
        </button>
      </div>
    </form>
  );
}
