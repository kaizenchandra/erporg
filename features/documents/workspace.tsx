"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { RetryScanForm } from "./retry-scan";
import { DownloadButton } from "./download";
import { VersionHistory } from "./versions";
import { UploadForm } from "./upload";
import { DocumentRetention } from "./retention";
import { DocumentMaintenanceForm } from "./maintenance-form";
import { DocumentTemplates } from "./templates";
import { DocumentTemplateForm } from "./template-form";
import type {
  EmployeeDocument as Document,
  DocumentPage as Page,
  DocumentAudit as Audit,
} from "./contracts";
export function DocumentsWorkspace({ actor }: { actor: Actor }) {
  const [search, setSearch] = useState(""),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [uploading, setUploading] = useState(false),
    [notice, setNotice] = useState("");
  const key = JSON.stringify([
    actor.subject,
    actor.tenant,
    actor.role,
    search,
    page,
    revision,
  ]);
  const [result, setResult] = useState<{
    key: string;
    data?: Page;
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    const c = new AbortController();
    const query = new URLSearchParams({
      search,
      page: String(page),
      size: "8",
    });
    api<Page>(`/api/v1/documents?${query}`, {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!c.signal.aborted) setResult({ key, data });
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error: e instanceof Error ? e.message : "Documents unavailable.",
          });
      });
    return () => c.abort();
  }, [key, search, page]);
  return (
    <section className="department-reference" aria-label="Employee documents">
      <div className="section-heading">
        <h3>{actor.role === "HR" ? "Employee documents" : "Your documents"}</h3>
        <button className="button primary" onClick={() => setUploading(true)}>
          Upload your document
        </button>
      </div>
      <p className="muted">
        Local development storage · UTF-8 .txt only, up to 16 KiB. Downloads
        require a clean ClamAV result. Quarantined and rejected files cannot be
        downloaded. HR can inspect tenant documents; managers see only their
        own.
      </p>
      {notice && <p role="status">{notice}</p>}
      <DocumentTemplates
        actor={actor}
        revision={revision}
        onChanged={() => setRevision((n) => n + 1)}
      />
      <div className="position-filters document-filters">
        <label>
          Search documents
          <input
            maxLength={120}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
        </label>
      </div>
      {!current ? (
        <p role="status">Loading documents…</p>
      ) : current.error ? (
        <div role="alert" className="error">
          {current.error}{" "}
          <button onClick={() => setRevision((n) => n + 1)}>
            Retry documents
          </button>
        </div>
      ) : (
        <>
          <p>
            {current.data!.total} matching latest documents · Filename A to Z ·
            Page {page + 1}
          </p>
          {current.data!.items.length ? (
            <div className="detail-grid">
              {current.data!.items.map((d) => (
                <DocumentCard
                  key={d.id}
                  document={d}
                  actor={actor}
                  onRescanned={(result) => {
                    setNotice(
                      `Document result recorded: version ${result.version}, ${result.status}. Current availability is shown in the catalogue.`,
                    );
                    setRevision((n) => n + 1);
                  }}
                />
              ))}
            </div>
          ) : (
            <p>
              No matching documents. Upload a synthetic text file or adjust the
              search.
            </p>
          )}
          <div className="position-pagination">
            <button
              className="button secondary"
              disabled={!page}
              onClick={() => setPage((n) => n - 1)}
            >
              Previous documents
            </button>
            <button
              className="button secondary"
              disabled={(page + 1) * 8 >= current.data!.total}
              onClick={() => setPage((n) => n + 1)}
            >
              Next documents
            </button>
          </div>
        </>
      )}
      {uploading && (
        <UploadForm
          onClose={() => setUploading(false)}
          onSaved={(d) => {
            setUploading(false);
            setPage(0);
            setRevision((n) => n + 1);
            setNotice(
              `Document uploaded: ${d.status}. ${d.status === "AVAILABLE" ? "Authorised download is available." : "Download is blocked; contact HR operations about scanning."}`,
            );
          }}
        />
      )}
    </section>
  );
}
function DocumentCard({
  document: d,
  actor,
  onRescanned,
}: {
  document: Document;
  actor: Actor;
  onRescanned: (result: Document) => void;
}) {
  const [retrying, setRetrying] = useState(false);
  const [versioning, setVersioning] = useState(false);
  const [linking, setLinking] = useState(false);
  const [correcting, setCorrecting] = useState(false);
  const [governing, setGoverning] = useState(false);
  const [historyRevision, setHistoryRevision] = useState(0);
  const [open, setOpen] = useState(false),
    [history, setHistory] = useState<Audit[] | null>(null),
    [error, setError] = useState("");
  useEffect(() => {
    if (!open) return;
    const c = new AbortController();
    api<Audit[]>(`/api/v1/documents/${d.id}/history`, {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!c.signal.aborted) setHistory(data);
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setError(e instanceof Error ? e.message : "History unavailable.");
      });
    return () => c.abort();
  }, [open, d.id, d.scanAttempts, historyRevision]);
  return (
    <article className="benefit-card document-card">
      <h4>{d.filename}</h4>
      <p>
        {d.employeeId} · Version {d.version} · {d.bytes} bytes ·{" "}
        <strong>{d.status}</strong>
      </p>
      <p>{d.reason}</p>
      <p>
        {d.scanner} · Scan retries {d.scanAttempts} of 3
      </p>
      {actor.role === "HR" &&
        d.status === "QUARANTINED" &&
        d.scanAttempts < 3 && (
          <button
            className="button secondary"
            onClick={() => setRetrying(true)}
          >
            Retry document scan
          </button>
        )}
      {d.status === "QUARANTINED" && d.scanAttempts >= 3 && (
        <p>
          Retry limit reached. Contact document operations; no manual download
          bypass is available.
        </p>
      )}
      {retrying && (
        <RetryScanForm
          document={d}
          onClose={() => setRetrying(false)}
          onSaved={onRescanned}
        />
      )}
      {d.status === "AVAILABLE" && (
        <DownloadButton
          document={d}
          onDownloaded={() => {
            setHistory(null);
            setHistoryRevision((n) => n + 1);
          }}
        />
      )}
      {d.version < 100 && (
        <button
          className="button secondary"
          onClick={() => setVersioning(true)}
        >
          Upload new version
        </button>
      )}
      <VersionHistory document={d} />
      {actor.role === "HR" && (
        <button className="button secondary" onClick={() => setGoverning(true)}>
          Retention and legal holds
        </button>
      )}
      {governing && (
        <DocumentRetention
          document={d}
          actor={actor}
          onClose={() => setGoverning(false)}
        />
      )}
      {actor.role === "HR" && d.status === "AVAILABLE" && (
        <button
          className="button secondary"
          onClick={() => setCorrecting(true)}
        >
          Correct classification
        </button>
      )}
      {correcting && (
        <DocumentMaintenanceForm
          document={d}
          onClose={() => setCorrecting(false)}
          onSaved={() => {
            setCorrecting(false);
            onRescanned(d);
          }}
        />
      )}
      {d.status === "AVAILABLE" && (
        <button className="button secondary" onClick={() => setLinking(true)}>
          Link to template
        </button>
      )}
      {linking && (
        <DocumentTemplateForm
          document={d}
          onClose={() => setLinking(false)}
          onSaved={() => {
            setLinking(false);
            onRescanned(d);
          }}
        />
      )}
      {versioning && (
        <UploadForm
          versionOf={d}
          onClose={() => setVersioning(false)}
          onSaved={onRescanned}
        />
      )}
      {d.status !== "AVAILABLE" && (
        <p>Download blocked. No clean scan result.</p>
      )}
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
      <details open={open} onToggle={(e) => setOpen(e.currentTarget.open)}>
        <summary>Document audit (latest 100)</summary>
        {history ? (
          history.map((h, i) => (
            <p key={i}>
              {h.action} · {h.actor} · {h.reason} ·{" "}
              {new Date(h.recordedAt).toISOString()}
            </p>
          ))
        ) : (
          <p role="status">Loading audit…</p>
        )}
      </details>
    </article>
  );
}
