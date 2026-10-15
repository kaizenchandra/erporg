"use client";
import { useEffect, useRef, useState } from "react";
import { Modal, formatDate } from "@/components/ui/primitives";
import { api } from "@/lib/api";
import type { Employee } from "@/lib/contracts";
export type OnboardingCompletion = {
  hireId: string;
  employeeId: string;
  actor: string;
  reason: string | null;
  inputVersion: number;
  newVersion: number;
  oldStatus: string;
  newStatus: string;
  effectiveDate: string;
  recordedAt: string;
  correlationId: string;
};
export function CompleteOnboardingDialog({
  hireId,
  employeeId,
  onClose,
  onSaved,
}: {
  hireId: string;
  employeeId: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [employee, setEmployee] = useState<Employee | null>(null),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [key] = useState(() => crypto.randomUUID());
  const summary = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    api<Employee>(`/api/v1/employees/${encodeURIComponent(employeeId)}`, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!controller.signal.aborted) setEmployee(data);
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setError(
            err instanceof Error
              ? err.message
              : "Unable to read employee. Reopen to retry.",
          );
      });
    return () => controller.abort();
  }, [employeeId]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const close = () => {
    if (
      !busy &&
      (!reason || window.confirm("Discard the unsaved completion reason?"))
    )
      onClose();
  };
  return (
    <Modal title="Complete onboarding" onClose={close}>
      <form
        className="change-form"
        onSubmit={async (event) => {
          event.preventDefault();
          if (busy || !employee) return;
          setBusy(true);
          setError("");
          try {
            await api<OnboardingCompletion>(
              `/api/v1/hiring/requests/${hireId}/complete-onboarding`,
              {
                method: "POST",
                body: JSON.stringify({
                  reason,
                  version: employee.version,
                  idempotencyKey: key,
                }),
              },
            );
            onSaved();
          } catch (err) {
            setError(
              err instanceof Error
                ? err.message
                : "Completion failed. Your reason is preserved; review and retry.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        {error && (
          <div
            className="error"
            role="alert"
            tabIndex={-1}
            ref={summary}
            aria-label="Onboarding completion errors"
          >
            {error} Your reason is preserved.
          </div>
        )}
        {!employee && !error && <p role="status">Loading employee status…</p>}
        {employee && (
          <>
            <h3>{employee.name}</h3>
            <p>
              {employee.id} · Current status {employee.status}
            </p>
            <p>Employment start: {formatDate(employee.startDate)}</p>
            {employee.status === "Onboarding" ? (
              <>
                <p>
                  This confirms one employee is ready to start and changes their
                  status to Active. Confirmation is permitted on or after their
                  employment start date. Payroll and sign-in setup remain
                  separate.
                </p>
                <label>
                  Completion reason <span>*</span>
                  <textarea
                    required
                    maxLength={500}
                    rows={3}
                    value={reason}
                    onChange={(event) => setReason(event.target.value)}
                  />
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
                    {busy ? "Completing…" : "Confirm Active status"}
                  </button>
                </div>
              </>
            ) : (
              <p>
                This employee is {employee.status}. Inspect their employment
                history for the recorded transition.
              </p>
            )}
          </>
        )}
      </form>
    </Modal>
  );
}
