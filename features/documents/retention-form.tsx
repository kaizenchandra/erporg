import { useEffect, useId, useRef, useState } from "react";
import { Modal } from "@/components/ui/primitives";
import { api, ApiError } from "@/lib/api";
import type { RetentionEvent, RetentionReview } from "./retention-contracts";
export function RetentionForm({
  documentId,
  review,
  action,
  holdId,
  onCancel,
  onSaved,
}: {
  documentId: string;
  review: RetentionReview;
  action: string;
  holdId?: string;
  onCancel: () => void;
  onSaved: () => void;
}) {
  const [date, setDate] = useState(review.retainUntil ?? ""),
    [basis, setBasis] = useState(review.basis ?? ""),
    [caseReference, setCase] = useState(""),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [key] = useState(() => crypto.randomUUID());
  const id = useId(),
    summary = useRef<HTMLDivElement>(null),
    request = useRef<AbortController | null>(null);
  const dirty =
    !!reason ||
    !!caseReference ||
    date !== (review.retainUntil ?? "") ||
    basis !== (review.basis ?? "");
  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [dirty]);
  const cancel = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard this unsaved governance command?"))
    )
      onCancel();
  };
  const props = (f: string) => ({
    id: `${id}-${f}`,
    "aria-invalid": !!fields[f],
    "aria-describedby": fields[f] ? `${id}-${f}-error` : undefined,
  });
  const fieldError = (f: string) =>
    fields[f] && (
      <span className="error" id={`${id}-${f}-error`}>
        {fields[f]}
      </span>
    );
  return (
    <Modal title="Document governance command" onClose={cancel}>
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFields({});
          const c = new AbortController();
          request.current = c;
          try {
            await api<RetentionEvent>(
              `/api/v1/documents/${documentId}/retention`,
              {
                method: "POST",
                signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
                body: JSON.stringify({
                  action,
                  expectedVersion: review.version,
                  retainUntil: action === "SET_POLICY" ? date || null : null,
                  basis: action === "SET_POLICY" ? basis : null,
                  caseReference: action === "PLACE_HOLD" ? caseReference : null,
                  holdId: action === "RELEASE_HOLD" ? holdId : null,
                  reason,
                  idempotencyKey: key,
                }),
              },
            );
            if (!c.signal.aborted) onSaved();
          } catch (e) {
            if (!c.signal.aborted) {
              setError(
                e instanceof Error ? e.message : "Governance command failed.",
              );
              setFields(e instanceof ApiError ? e.fieldErrors : {});
            }
          } finally {
            setBusy(false);
          }
        }}
      >
        <h3>
          {action === "SET_POLICY"
            ? "Set retain-until directive"
            : action === "PLACE_HOLD"
              ? "Place legal hold"
              : "Release legal hold"}
        </h3>
        <p>
          Employee {review.employeeId} · Entire document chain, including
          historical and future versions · Expected governance version{" "}
          {review.version}.
        </p>
        <p>
          {action === "SET_POLICY"
            ? "Provide an explicit authorised policy basis and date. Dates may only extend; no statutory period is supplied. Review due never authorises deletion."
            : action === "PLACE_HOLD"
              ? "Records a preservation hold for one case. Other active holds remain independent. No file is deleted or made public."
              : "Releases only this hold. A different HR employee must decide. Other holds and retention directives remain in force; no deletion becomes available."}
        </p>
        {error && (
          <div
            ref={summary}
            tabIndex={-1}
            role="alert"
            aria-label="Document governance errors"
            className="error"
          >
            {error} Your entries are preserved. Refresh the review if its
            version changed.
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
        {action === "SET_POLICY" && (
          <>
            <label>
              Retain until *
              <input
                {...props("retainUntil")}
                type="date"
                required
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
              {fieldError("retainUntil")}
            </label>
            <label>
              Policy basis *
              <textarea
                {...props("basis")}
                rows={3}
                required
                maxLength={1000}
                value={basis}
                onChange={(e) => setBasis(e.target.value)}
              />
              {fieldError("basis")}
            </label>
          </>
        )}
        {action === "PLACE_HOLD" && (
          <label>
            Case reference *
            <input
              {...props("caseReference")}
              required
              maxLength={80}
              value={caseReference}
              onChange={(e) => setCase(e.target.value)}
            />
            {fieldError("caseReference")}
          </label>
        )}
        {action === "RELEASE_HOLD" && (
          <p>
            Case: {review.holds.find((h) => h.id === holdId)?.caseReference}
          </p>
        )}
        <label>
          Governance reason *
          <textarea
            {...props("reason")}
            rows={3}
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {fieldError("reason")}
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={cancel}
          >
            Back to review
          </button>
          <button className="button primary" disabled={busy}>
            {busy
              ? "Recording…"
              : action === "SET_POLICY"
                ? "Confirm retention directive"
                : action === "PLACE_HOLD"
                  ? "Confirm legal hold"
                  : "Confirm hold release"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
