import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { DownloadButton } from "./download";
import type { EmployeeDocument } from "./contracts";
export function VersionHistory({
  document: d,
}: {
  document: EmployeeDocument;
}) {
  const [open, setOpen] = useState(false),
    [revision, setRevision] = useState(0),
    [result, setResult] = useState<{
      key: string;
      items?: EmployeeDocument[];
      error?: string;
    }>();
  const key = JSON.stringify([d.id, d.version, revision]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    api<EmployeeDocument[]>(`/api/v1/documents/${d.id}/versions`, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((items) => {
        if (!controller.signal.aborted) setResult({ key, items });
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Version history unavailable.",
          });
      });
    return () => controller.abort();
  }, [d.id, key, open]);
  return (
    <details
      className="document-versions"
      open={open}
      onToggle={(e) => setOpen(e.currentTarget.open)}
    >
      <summary>Document versions</summary>
      <p>
        Latest means latest upload, not legal validity or approval. Each version
        has independent scan results; historical downloads are explicit.
      </p>
      {!current ? (
        <p role="status">Loading versions…</p>
      ) : current.error ? (
        <p className="error" role="alert">
          {current.error}{" "}
          <button onClick={() => setRevision((n) => n + 1)}>
            Retry version history
          </button>
        </p>
      ) : (
        current.items!.map((v) => (
          <div className="history-event" key={v.id}>
            <div>
              <strong>
                Version {v.version}
                {v.version === current.items![0].version
                  ? " · Latest uploaded"
                  : " · Historical"}
              </strong>
              <p>
                {v.filename} · {v.status} ·{" "}
                {new Date(v.recordedAt).toISOString()}
              </p>
              <p>{v.reason}</p>
              {v.status === "AVAILABLE" ? (
                <DownloadButton
                  document={v}
                  label={`Download version ${v.version}`}
                />
              ) : (
                <p>Download blocked for this version.</p>
              )}
            </div>
          </div>
        ))
      )}
    </details>
  );
}
