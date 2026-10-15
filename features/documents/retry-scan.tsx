import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import type { EmployeeDocument as Document } from "./contracts";
export function RetryScanForm({
  document: d,
  onClose,
  onSaved,
}: {
  document: Document;
  onClose: () => void;
  onSaved: (result: Document) => void;
}) {
  const [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const [key] = useState(() => crypto.randomUUID()),
    id = useId(),
    summary = useRef<HTMLDivElement>(null),
    request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (reason) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [reason]);
  const close = () => {
    if (
      !busy &&
      (!reason || window.confirm("Discard this unsaved scan retry?"))
    )
      onClose();
  };
  return (
    <Modal title="Retry quarantined document scan" onClose={close}>
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          const controller = new AbortController();
          request.current = controller;
          setBusy(true);
          setError("");
          setFields({});
          try {
            const result = await api<Document>(
              `/api/v1/documents/${d.id}/scan-retries`,
              {
                method: "POST",
                signal: AbortSignal.any([
                  controller.signal,
                  AbortSignal.timeout(10000),
                ]),
                body: JSON.stringify({
                  expectedAttempt: d.scanAttempts,
                  reason,
                  idempotencyKey: key,
                }),
              },
            );
            if (!controller.signal.aborted) onSaved(result);
          } catch (e) {
            if (!controller.signal.aborted) {
              setError(e instanceof Error ? e.message : "Scan retry failed.");
              setFields(e instanceof ApiError ? e.fieldErrors : {});
            }
          } finally {
            setBusy(false);
          }
        }}
      >
        <p id={`${id}-expectedAttempt`} tabIndex={-1}>
          {d.filename} · Employee {d.employeeId}. This retries one stored file,
          attempt {d.scanAttempts + 1} of 3. A clean result permits authorised
          download; an unavailable or infected result remains blocked. The
          original upload and scan history are preserved.
        </p>
        {error && (
          <div
            ref={summary}
            tabIndex={-1}
            role="alert"
            aria-label="Document scan retry errors"
            className="error"
          >
            {error} Your reason is preserved. Retry with the same command after
            a connection failure; close and refresh documents after a conflict.
            <ul>
              {Object.entries(fields).map(([f, m]) => (
                <li key={f}>
                  <a
                    href={`#${id}-${f}`}
                    onClick={(e) => {
                      e.preventDefault();
                      const target = document.getElementById(`${id}-${f}`);
                      if (target) target.focus();
                      else summary.current?.focus();
                    }}
                  >
                    {m}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        <label>
          Scan retry reason *
          <textarea
            id={`${id}-reason`}
            required
            maxLength={500}
            rows={3}
            value={reason}
            aria-invalid={!!fields.reason}
            aria-describedby={fields.reason ? `${id}-reason-error` : undefined}
            onChange={(e) => setReason(e.target.value)}
          />
          {fields.reason && (
            <span className="error" id={`${id}-reason-error`}>
              {fields.reason}
            </span>
          )}
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "Rescanning…" : "Confirm scan retry"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
