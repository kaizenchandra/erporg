import { useEffect, useId, useRef, useState } from "react";
import { Modal } from "@/components/ui/primitives";
import { api, ApiError } from "@/lib/api";
import type { EmployeeDocument } from "./contracts";
import type { DocumentTemplate } from "./template-contracts";
export function DocumentTemplateForm({
  document: d,
  onClose,
  onSaved,
}: {
  document?: EmployeeDocument;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [code, setCode] = useState(""),
    [title, setTitle] = useState(""),
    [body, setBody] = useState(""),
    [required, setRequired] = useState(false),
    [effectiveDate, setDate] = useState(""),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [options, setOptions] = useState<DocumentTemplate[] | null>(null),
    [optionError, setOptionError] = useState(""),
    [optionRevision, setOptionRevision] = useState(0);
  const id = useId(),
    summary = useRef<HTMLDivElement>(null),
    request = useRef<AbortController | null>(null),
    [key] = useState(() => crypto.randomUUID());
  const dirty = !!(
    code ||
    title ||
    body ||
    effectiveDate ||
    reason ||
    required
  );
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
  useEffect(() => {
    if (!d) return;
    const c = new AbortController();
    api<DocumentTemplate[]>("/api/v1/document-templates", {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((v) => {
        if (!c.signal.aborted) {
          setOptions(v);
          setOptionError("");
        }
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setOptionError(
            e instanceof Error ? e.message : "Templates unavailable.",
          );
      });
    return () => c.abort();
  }, [d, optionRevision]);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard these unsaved document settings?"))
    )
      onClose();
  };
  const control = (field: string) => ({
    id: `${id}-${field}`,
    "aria-invalid": !!fields[field],
    "aria-describedby": fields[field] ? `${id}-${field}-error` : undefined,
  });
  const fieldError = (field: string) =>
    fields[field] && (
      <span className="error" id={`${id}-${field}-error`}>
        {fields[field]}
      </span>
    );
  return (
    <Modal
      title={d ? "Link document to template" : "Create document template"}
      onClose={close}
    >
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          const c = new AbortController();
          request.current = c;
          setBusy(true);
          setError("");
          setFields({});
          try {
            await api(
              d
                ? `/api/v1/document-templates/${encodeURIComponent(code || "invalid")}/evidence`
                : "/api/v1/document-templates",
              {
                method: "POST",
                signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
                body: JSON.stringify(
                  d
                    ? {
                        documentId: d.id,
                        expectedVersion: d.version,
                        reason,
                        idempotencyKey: key,
                      }
                    : {
                        code,
                        title,
                        body,
                        required,
                        effectiveDate: effectiveDate || null,
                        reason,
                        idempotencyKey: key,
                      },
                ),
              },
            );
            if (!c.signal.aborted) onSaved();
          } catch (e) {
            if (!c.signal.aborted) {
              setError(
                e instanceof Error
                  ? e.message
                  : "Command failed. Retry or contact document operations.",
              );
              setFields(e instanceof ApiError ? e.fieldErrors : {});
            }
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          {d
            ? `${d.filename} · Employee ${d.employeeId} · Version ${d.version}. Classifies this one document chain. Future versions determine completeness. HR can correct a classification with retained history; this is not content approval.`
            : "Creates an immutable text template for every employee record in this tenant from its effective date. Required means a configured company check, not statutory compliance. HR can append revisions or retirement from template history. Targeting is not available."}
        </p>
        {error && (
          <div
            ref={summary}
            role="alert"
            aria-label="Document template errors"
            className="error"
            tabIndex={-1}
          >
            {error} Your entries are preserved. Correct the fields and retry.
            <ul>
              {Object.entries(fields).map(([f, m]) => (
                <li key={f}>
                  <a
                    href={`#${id}-${f}`}
                    onClick={(e) => {
                      e.preventDefault();
                      document.getElementById(`${id}-${f}`)?.focus();
                    }}
                  >
                    {m}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {d ? (
          <>
            <label>
              Document template *
              <select
                {...control("code")}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
              >
                <option value="">Choose a configured template</option>
                {options
                  ?.filter((t) => !t.retired)
                  .map((t) => (
                    <option key={t.code} value={t.code}>
                      {t.code} · {t.title}
                    </option>
                  ))}
              </select>
              {fieldError("code")}
            </label>
            {optionError ? (
              <p role="alert">
                {optionError}{" "}
                <button
                  type="button"
                  onClick={() => setOptionRevision((n) => n + 1)}
                >
                  Retry templates
                </button>
              </p>
            ) : !options ? (
              <p role="status">Loading templates…</p>
            ) : !options.length ? (
              <p>
                No templates configured. Ask HR to create one before linking.
              </p>
            ) : null}
          </>
        ) : (
          <>
            <label>
              Template code *
              <input
                {...control("code")}
                maxLength={32}
                value={code}
                onChange={(e) => setCode(e.target.value)}
                required
              />
              {fieldError("code")}
            </label>
            <label>
              Template title *
              <input
                {...control("title")}
                maxLength={100}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                required
              />
              {fieldError("title")}
            </label>
            <label>
              Template text *
              <textarea
                {...control("body")}
                rows={6}
                maxLength={4000}
                value={body}
                onChange={(e) => setBody(e.target.value)}
                required
              />
              {fieldError("body")}
            </label>
            <label>
              Effective date *
              <input
                {...control("effectiveDate")}
                type="date"
                value={effectiveDate}
                onChange={(e) => setDate(e.target.value)}
                required
              />
              {fieldError("effectiveDate")}
            </label>
            <label className="template-required">
              <input
                {...control("required")}
                type="checkbox"
                checked={required}
                onChange={(e) => setRequired(e.target.checked)}
              />{" "}
              Required for completeness{fieldError("required")}
            </label>
          </>
        )}
        <label>
          Configuration reason *
          <textarea
            {...control("reason")}
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            required
          />
          {fieldError("reason")}
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
          <button
            className="button primary"
            disabled={busy || (!!d && (!options?.length || !!optionError))}
          >
            {busy ? "Saving…" : d ? "Confirm document link" : "Create template"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
