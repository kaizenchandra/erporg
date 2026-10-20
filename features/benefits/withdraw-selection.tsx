"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import type { Selection } from "./selections";
export function WithdrawSelectionForm({
  selection,
  onClose,
  onSaved,
}: {
  selection: Selection;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const summary = useRef<HTMLDivElement>(null),
    attempt = useRef<{ fingerprint: string; key: string } | null>(null),
    id = useId();
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const guard = (e: BeforeUnloadEvent) => {
      if (reason) e.preventDefault();
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [reason]);
  return (
    <Modal
      title="Withdraw pending benefit selection"
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          (!reason || window.confirm("Discard the unsaved withdrawal reason?"))
        )
          onClose();
      }}
    >
      <form
        className="change-form"
        noValidate
        aria-label="Benefit selection withdrawal"
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFields({});
          const body = { version: selection.version, reason },
            fingerprint = JSON.stringify(body);
          if (attempt.current?.fingerprint !== fingerprint)
            attempt.current = { fingerprint, key: crypto.randomUUID() };
          try {
            await api(
              `/api/v1/benefits/selections/${encodeURIComponent(selection.id)}/withdrawal`,
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
            if (e instanceof ApiError) setFields(e.fieldErrors);
            setError(
              (e instanceof Error
                ? e.message
                : "Withdrawal could not be confirmed") +
                (e instanceof ApiError && e.status === 400
                  ? " Your reason is preserved. Correct the highlighted fields and submit again."
                  : " Your reason is preserved. Retry unchanged values to recover the outcome, or close and refresh history."),
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          This withdraws only your pending selection for{" "}
          <strong>{selection.planName}</strong>. Dependants, the original
          selection and its audit remain retained. No payroll deduction or
          provider cancellation is created. HR review and withdrawal cannot both
          win.
        </p>
        {error && (
          <div
            role="alert"
            aria-label="Benefit withdrawal errors"
            className="error"
            ref={summary}
            tabIndex={-1}
          >
            {error}
            {Object.entries(fields).map(([field, message]) => (
              <p key={field}>{message}</p>
            ))}
          </div>
        )}
        <label htmlFor={id}>Withdrawal reason (required)</label>
        <textarea
          id={id}
          maxLength={500}
          required
          disabled={busy}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          aria-invalid={!!fields.reason}
          aria-describedby={fields.reason ? `${id}-error` : undefined}
        />
        {fields.reason && (
          <p className="error" id={`${id}-error`}>
            {fields.reason}
          </p>
        )}
        <p>
          After confirmed withdrawal, you may submit a fresh eligible selection.
          Approved or rejected selections cannot be withdrawn here.
        </p>
        <button className="button primary" disabled={busy}>
          {busy ? "Withdrawing…" : "Confirm benefit withdrawal"}
        </button>
      </form>
    </Modal>
  );
}
