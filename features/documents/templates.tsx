import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { TemplateRevisions } from "./template-revisions";
import { DocumentTemplateForm } from "./template-form";
import type {
  DocumentTemplate,
  DocumentCompleteness,
  DocumentEvidence,
} from "./template-contracts";
export function DocumentTemplates({
  actor,
  revision,
  onChanged,
}: {
  actor: Actor;
  revision: number;
  onChanged: () => void;
}) {
  const [creating, setCreating] = useState(false),
    [input, setInput] = useState(""),
    [employeeId, setEmployee] = useState(""),
    [retry, setRetry] = useState(0),
    [open, setOpen] = useState(false);
  const key = JSON.stringify([
    actor.subject,
    actor.tenant,
    actor.role,
    employeeId,
    revision,
    retry,
  ]);
  const [result, setResult] = useState<{
    key: string;
    templates?: DocumentTemplate[];
    completeness?: DocumentCompleteness;
    history?: DocumentEvidence[];
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    if (!open) return;
    const c = new AbortController(),
      query = new URLSearchParams(employeeId ? { employeeId } : {});
    const options = {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    };
    Promise.all([
      api<DocumentTemplate[]>("/api/v1/document-templates", options),
      api<DocumentCompleteness>(
        `/api/v1/document-templates/completeness?${query}`,
        options,
      ),
      api<DocumentEvidence[]>(
        `/api/v1/document-templates/evidence?${query}`,
        options,
      ),
    ])
      .then(([templates, completeness, history]) => {
        if (!c.signal.aborted)
          setResult({ key, templates, completeness, history });
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Template checks unavailable.",
          });
      });
    return () => c.abort();
  }, [open, key, employeeId]);
  return (
    <section
      aria-label="Document templates and completeness"
      className="document-template-panel"
    >
      <button
        className="button secondary"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        Templates and completeness
      </button>
      {open && (
        <>
          <div className="section-heading">
            <h3>Document templates and completeness</h3>
            {actor.role === "HR" && (
              <button
                className="button primary"
                onClick={() => setCreating(true)}
              >
                Create document template
              </button>
            )}
          </div>
          <p>
            Company-configured checks only. Present means the linked chain has a
            clean latest scan, not that its contents are approved or legally
            sufficient. Blank employee selection checks your own record.
          </p>
          {actor.role === "HR" && (
            <form
              className="position-filters document-filters"
              onSubmit={(e) => {
                e.preventDefault();
                setEmployee(input.trim());
                setRetry((n) => n + 1);
              }}
            >
              <label>
                Employee ID for completeness
                <input
                  value={input}
                  maxLength={40}
                  onChange={(e) => setInput(e.target.value)}
                />
              </label>
              <button className="button secondary">Check employee</button>
            </form>
          )}
          {!current ? (
            <p role="status">Loading template checks…</p>
          ) : current.error ? (
            <p role="alert" className="error">
              {current.error}{" "}
              <button onClick={() => setRetry((n) => n + 1)}>
                Retry template checks
              </button>
            </p>
          ) : (
            <>
              <p>
                Employee {current.completeness!.employeeId} · As of{" "}
                {current.completeness!.asOf} (UTC). {current.templates!.length}{" "}
                configured templates.
              </p>
              {!current.templates!.length && (
                <p>
                  No templates configured. Completeness is not assessed until HR
                  defines requirements.
                </p>
              )}
              <div className="detail-grid">
                {current.templates!.map((t) => {
                  const check = current.completeness!.items.find(
                    (i) => i.code === t.code,
                  )!;
                  return (
                    <article className="benefit-card" key={t.code}>
                      <h4>
                        {t.code} · {t.title}
                      </h4>
                      <p>
                        {t.required ? "Required" : "Optional"} · Effective{" "}
                        {t.effectiveDate} ·{" "}
                        <strong>{check.state.replaceAll("_", " ")}</strong>
                      </p>
                      <pre className="document-template-text">{t.body}</pre>
                      <p>
                        Current configuration revision {t.version}
                        {t.retired ? " · Retired" : ""}
                      </p>
                      <TemplateRevisions
                        key={`${t.code}-${revision}`}
                        code={t.code}
                        isHr={actor.role === "HR"}
                        onChanged={onChanged}
                      />
                      {check.version && (
                        <p>
                          Linked latest version {check.version} ·{" "}
                          {check.scanStatus}
                        </p>
                      )}
                      <p>
                        Configuration: {t.actor} · {t.reason} ·{" "}
                        {new Date(t.recordedAt).toISOString()}
                      </p>
                    </article>
                  );
                })}
              </div>
              {current.history!.length > 0 && (
                <>
                  <h4>Document classification history (latest 100)</h4>
                  {current.history!.map((h) => (
                    <p key={h.rootId}>
                      {h.code} · Classification {h.classificationVersion} ·
                      Linked version {h.linkedVersion} · {h.actor} · {h.reason}{" "}
                      · {new Date(h.recordedAt).toISOString()}
                    </p>
                  ))}
                </>
              )}
            </>
          )}
        </>
      )}
      {creating && (
        <DocumentTemplateForm
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            onChanged();
          }}
        />
      )}
    </section>
  );
}
