import { useEffect, useId, useRef, useState } from "react";
import { Modal } from "@/components/ui/primitives";
import { api, ApiError } from "@/lib/api";
import type { EmployeeDocument } from "./contracts";
import type { DocumentTemplate, DocumentEvidence } from "./template-contracts";
export function DocumentMaintenanceForm({
  template,
  document: d,
  onClose,
  onSaved,
}: {
  template?: DocumentTemplate;
  document?: EmployeeDocument;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [code, setCode] = useState(template?.code ?? ""),
    [title, setTitle] = useState(template?.title ?? ""),
    [body, setBody] = useState(template?.body ?? ""),
    [required, setRequired] = useState(template?.required ?? false),
    [retired, setRetired] = useState(false),
    [date, setDate] = useState(
      template
        ? [template.effectiveDate, new Date().toISOString().slice(0, 10)]
            .sort()
            .at(-1)!
        : "",
    ),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({}),
    [options, setOptions] = useState<DocumentTemplate[] | null>(null),
    [history, setHistory] = useState<{
      code: string;
      items: DocumentEvidence[];
    }>(),
    [loadError, setLoadError] = useState(""),
    [optionsError, setOptionsError] = useState(""),
    [retry, setRetry] = useState(0);
  const id = useId(),
    summary = useRef<HTMLDivElement>(null),
    request = useRef<AbortController | null>(null),
    [key] = useState(() => crypto.randomUUID());
  const dirty =
    !!reason ||
    retired ||
    (!!d && !!code) ||
    (!!template &&
      (title !== template.title ||
        body !== template.body ||
        required !== template.required ||
        date !==
          [template.effectiveDate, new Date().toISOString().slice(0, 10)]
            .sort()
            .at(-1)));
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
          setOptionsError("");
        }
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setOptionsError(
            e instanceof Error ? e.message : "Templates unavailable.",
          );
      });
    return () => c.abort();
  }, [d, retry]);
  useEffect(() => {
    if (!d || !code) return;
    const c = new AbortController();
    api<DocumentEvidence[]>(
      `/api/v1/document-templates/${encodeURIComponent(code)}/evidence-history?${new URLSearchParams({ employeeId: d.employeeId })}`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((items) => {
        if (!c.signal.aborted) {
          setHistory({ code, items });
          setLoadError("");
        }
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setLoadError(
            e instanceof Error
              ? e.message
              : "Classification history unavailable.",
          );
      });
    return () => c.abort();
  }, [d, code, retry]);
  const current = history?.code === code ? history.items : undefined;
  const latest = current?.reduce<DocumentEvidence | undefined>(
    (a, v) => (!a || v.classificationVersion > a.classificationVersion ? v : a),
    undefined,
  );
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard this unsaved document change?"))
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
      title={d ? "Correct document classification" : "Revise document template"}
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
              `/api/v1/document-templates/${encodeURIComponent(code || "invalid")}/${d ? "reclassifications" : "revisions"}`,
              {
                method: "POST",
                signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
                body: JSON.stringify(
                  d
                    ? {
                        employeeId: d.employeeId,
                        documentId: d.id,
                        expectedVersion: d.version,
                        expectedClassificationVersion:
                          latest?.classificationVersion ?? null,
                        reason,
                        idempotencyKey: key,
                      }
                    : {
                        expectedVersion: template!.version,
                        title,
                        body,
                        required,
                        retired,
                        effectiveDate: date || null,
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
                  : "Change failed. Refresh history and retry.",
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
            ? `Employee ${d.employeeId}: replace one requirement's classification with ${d.filename}, file version ${d.version}. Previous chains/audits remain reserved; this is not content approval.`
            : `${template!.code}: append revision ${template!.version + 1} after ${template!.version}. Applies to all tenant employee records on the effective date. Retirement stops completeness requirements and is terminal; no files or evidence are deleted.`}
        </p>
        {error && (
          <div
            role="alert"
            aria-label="Document maintenance errors"
            tabIndex={-1}
            className="error"
            ref={summary}
          >
            {error} Your entries are preserved. Refresh history if versions
            changed.
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
        {d ? (
          <>
            <label>
              Requirement to correct *
              <select
                {...control("code")}
                required
                value={code}
                onChange={(e) => {
                  setCode(e.target.value);
                  setLoadError("");
                }}
              >
                <option value="">
                  Choose a template with existing classification
                </option>
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
            {code &&
              (!current ? (
                <p role="status">Loading classification history…</p>
              ) : latest ? (
                <p>
                  Replaces classification {latest.classificationVersion},
                  previously linked file version {latest.linkedVersion}. Earlier
                  chains remain in history.
                </p>
              ) : (
                <p>
                  No existing classification. Use Link to template for initial
                  classification.
                </p>
              ))}
            {(loadError || optionsError) && (
              <p role="alert">
                {loadError || optionsError}{" "}
                <button type="button" onClick={() => setRetry((n) => n + 1)}>
                  Retry correction context
                </button>
              </p>
            )}
          </>
        ) : (
          <>
            <label>
              Revised template title *
              <input
                {...control("title")}
                required
                maxLength={100}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
              />
              {fieldError("title")}
            </label>
            <label>
              Revised template text *
              <textarea
                {...control("body")}
                required
                rows={5}
                maxLength={4000}
                value={body}
                onChange={(e) => setBody(e.target.value)}
              />
              {fieldError("body")}
            </label>
            <label>
              Revision effective date *
              <input
                {...control("effectiveDate")}
                required
                type="date"
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
              {fieldError("effectiveDate")}
            </label>
            <label className="template-required">
              <input
                type="checkbox"
                checked={required}
                onChange={(e) => setRequired(e.target.checked)}
              />
              Required for completeness
            </label>
            <label className="template-required">
              <input
                type="checkbox"
                checked={retired}
                onChange={(e) => setRetired(e.target.checked)}
              />
              Retire on effective date (terminal)
            </label>
          </>
        )}
        <label>
          Change reason *
          <textarea
            {...control("reason")}
            required
            maxLength={500}
            rows={3}
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
            onClick={close}
          >
            Cancel
          </button>
          <button
            className="button primary"
            disabled={
              busy || (!!d && (!latest || !!loadError || !!optionsError))
            }
          >
            {busy
              ? "Saving…"
              : d
                ? "Confirm classification correction"
                : "Confirm template revision"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
