"use client";
import { useEffect, useId, useRef, useState } from "react";
import { Modal, formatDate } from "@/components/ui/primitives";
import { api, ApiError } from "@/lib/api";
import type { ChangeRequest } from "@/lib/contracts";
export function WithdrawRequest({
  request,
  onClose,
  onSaved,
}: {
  request: ChangeRequest;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const [fieldError, setFieldError] = useState("");
  const [busy, setBusy] = useState(false);
  const [key] = useState(() => crypto.randomUUID());
  const summary = useRef<HTMLDivElement>(null);
  const id = useId();
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {
      if (reason) event.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [reason]);
  const close = () => {
    if (!busy && (!reason || window.confirm("Discard your withdrawal reason?")))
      onClose();
  };
  return (
    <Modal title="Withdraw pending change" onClose={close}>
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFieldError("");
          try {
            await api(`/api/v1/requests/${request.id}/withdraw`, {
              method: "POST",
              body: JSON.stringify({ reason, idempotencyKey: key }),
            });
            onSaved();
          } catch (err) {
            setError(
              err instanceof Error
                ? err.message
                : "Unable to withdraw. Retry after checking request status.",
            );
            setFieldError(
              err instanceof ApiError ? (err.fieldErrors.reason ?? "") : "",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          This withdraws one pending change for {request.employeeName} (
          {request.employeeId}), effective {formatDate(request.effectiveDate)}.
          No employee values will be changed. An already decided request cannot
          be withdrawn.
        </p>
        {error && (
          <div
            className="error"
            role="alert"
            aria-label="Withdrawal errors"
            tabIndex={-1}
            ref={summary}
          >
            {error} Your reason is preserved. Check the latest status before
            retrying.
            {fieldError && (
              <ul>
                <li>
                  <a
                    href={`#${id}`}
                    onClick={(e) => {
                      e.preventDefault();
                      document.getElementById(id)?.focus();
                    }}
                  >
                    {fieldError}
                  </a>
                </li>
              </ul>
            )}
          </div>
        )}
        <label>
          Withdrawal reason <span>*</span>
          <textarea
            id={id}
            required
            maxLength={500}
            rows={3}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            aria-invalid={!!fieldError}
            aria-describedby={fieldError ? `${id}-error` : undefined}
          />
          {fieldError && (
            <span className="error" id={`${id}-error`}>
              {fieldError}
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
            Keep pending
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "Withdrawing…" : "Confirm withdrawal"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
