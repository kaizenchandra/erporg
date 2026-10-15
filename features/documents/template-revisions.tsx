import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { DocumentTemplate } from "./template-contracts";
import { DocumentMaintenanceForm } from "./maintenance-form";
export function TemplateRevisions({
  code,
  isHr,
  onChanged,
}: {
  code: string;
  isHr: boolean;
  onChanged: () => void;
}) {
  const [open, setOpen] = useState(false),
    [editing, setEditing] = useState(false),
    [revision, setRevision] = useState(0),
    [result, setResult] = useState<{
      key: string;
      items?: DocumentTemplate[];
      error?: string;
    }>();
  const key = JSON.stringify([code, revision]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    if (!open) return;
    const c = new AbortController();
    api<DocumentTemplate[]>(
      `/api/v1/document-templates/${encodeURIComponent(code)}/revisions`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((items) => {
        if (!c.signal.aborted) setResult({ key, items });
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Revision history unavailable.",
          });
      });
    return () => c.abort();
  }, [open, code, key]);
  return (
    <>
      <details
        className="document-revisions"
        open={open}
        onToggle={(e) => setOpen(e.currentTarget.open)}
      >
        <summary>Template revision history</summary>
        {!current ? (
          <p role="status">Loading revisions…</p>
        ) : current.error ? (
          <p role="alert">
            {current.error}{" "}
            <button onClick={() => setRevision((n) => n + 1)}>
              Retry revision history
            </button>
          </p>
        ) : (
          <>
            {isHr &&
              !current.items![0].retired &&
              current.items![0].version < 100 && (
                <button
                  className="button secondary"
                  onClick={() => setEditing(true)}
                >
                  Revise or retire template
                </button>
              )}
            {current.items!.map((t) => (
              <div key={t.version} className="history-event">
                <div>
                  <strong>
                    Revision {t.version} ·{" "}
                    {t.retired ? "Retirement" : "Active configuration"} ·
                    Effective {t.effectiveDate}
                  </strong>
                  <p>
                    {t.title} · {t.required ? "Required" : "Optional"}
                  </p>
                  <pre className="document-template-text">{t.body}</pre>
                  <p>
                    {t.actor} · {t.reason} ·{" "}
                    {new Date(t.recordedAt).toISOString()}
                  </p>
                </div>
              </div>
            ))}
          </>
        )}
      </details>
      {editing && current?.items && (
        <DocumentMaintenanceForm
          template={current.items[0]}
          onClose={() => setEditing(false)}
          onSaved={() => {
            setEditing(false);
            setRevision((n) => n + 1);
            onChanged();
          }}
        />
      )}
    </>
  );
}
