import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import type { EmployeeDocument as Document } from "./contracts";
export function UploadForm({
  onClose,
  onSaved,
  versionOf,
}: {
  onClose: () => void;
  onSaved: (d: Document) => void;
  versionOf?: Document;
}) {
  const [file, setFile] = useState<File | null>(null),
    [reason, setReason] = useState(""),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const [key] = useState(() => crypto.randomUUID()),
    id = useId(),
    summary = useRef<HTMLDivElement>(null);
  const dirty = !!file || !!reason;
  const uploadRequest = useRef<AbortController | null>(null);
  useEffect(() => () => uploadRequest.current?.abort(), []);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard this unsaved document upload?"))
    )
      onClose();
  };
  return (
    <Modal
      title={
        versionOf ? "Upload document version" : "Upload your private document"
      }
      onClose={close}
    >
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          const controller = new AbortController();
          uploadRequest.current = controller;
          setBusy(true);
          setError("");
          setFields({});
          try {
            if (!file || file.size < 1 || file.size > 16384)
              throw new ApiError("Choose a text file of 1–16384 bytes.", 400, {
                file: "Choose a text file of 1–16384 bytes.",
              });
            const content = new Uint8Array(await file.arrayBuffer());
            let binary = "";
            for (const byte of content) binary += String.fromCharCode(byte);
            if (controller.signal.aborted) return;
            const upload = {
              filename: file.name,
              mediaType: file.type,
              contentBase64: btoa(binary),
              reason,
              idempotencyKey: key,
            };
            const d = await api<Document>(
              versionOf
                ? `/api/v1/documents/${versionOf.id}/versions`
                : "/api/v1/documents",
              {
                method: "POST",
                signal: AbortSignal.any([
                  controller.signal,
                  AbortSignal.timeout(10000),
                ]),
                body: JSON.stringify(
                  versionOf
                    ? { expectedVersion: versionOf.version, upload }
                    : upload,
                ),
              },
            );
            if (!controller.signal.aborted) onSaved(d);
          } catch (e) {
            if (controller.signal.aborted) return;
            setError(e instanceof Error ? e.message : "Upload failed.");
            setFields(e instanceof ApiError ? e.fieldErrors : {});
          } finally {
            setBusy(false);
          }
        }}
      >
        {versionOf && (
          <p id={`${id}-expectedVersion`} tabIndex={-1}>
            {versionOf.filename} · Employee {versionOf.employeeId}. Replaces
            latest uploaded version {versionOf.version} with version{" "}
            {versionOf.version + 1}. Earlier files/scans/audits are retained. A
            rejected or quarantined new version is blocked; earlier clean
            versions remain available only through explicit version history.
          </p>
        )}
        <p>
          {versionOf
            ? "Upload a replacement for this authorised employee record."
            : "Uploads to your employee record."}{" "}
          UTF-8 .txt only, 16 KiB maximum, up to 100 stored files including
          versions per employee. Only clean scans allow retrieval. Production
          storage and retention are not configured. Link a clean document to a
          configured template from its document card.
        </p>
        {error && (
          <div
            role="alert"
            aria-label="Document upload errors"
            className="error"
            tabIndex={-1}
            ref={summary}
          >
            {error} Your entries are preserved. Correct the fields and retry.
            <ul>
              {Object.entries(fields).map(([f, m]) => (
                <li key={f}>
                  <a
                    href={`#${id}-${f}`}
                    onClick={(e) => {
                      e.preventDefault();
                      const target = document.getElementById(
                        `${id}-${f === "filename" || f === "mediaType" ? "file" : f}`,
                      );
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
        <label>
          Document file *
          <input
            id={`${id}-file`}
            type="file"
            required
            accept=".txt,text/plain"
            aria-invalid={
              !!fields.file || !!fields.filename || !!fields.mediaType
            }
            aria-describedby={fields.file ? `${id}-file-error` : undefined}
            onChange={(e) => setFile(e.target.files?.[0] ?? null)}
          />
          {fields.file && (
            <span className="error" id={`${id}-file-error`}>
              {fields.file}
            </span>
          )}
          {fields.filename && <span className="error">{fields.filename}</span>}
          {fields.mediaType && (
            <span className="error">{fields.mediaType}</span>
          )}
        </label>
        <label>
          Document upload reason *
          <textarea
            id={`${id}-reason`}
            required
            maxLength={500}
            rows={3}
            value={reason}
            aria-invalid={!!fields.reason}
            aria-describedby={fields.reason ? `${id}-reason-error` : undefined}
            onChange={(e) => setReason(e.target.value)}
          />
          {fields.reason && (
            <span className="error" id={`${id}-reason-error`}>
              {fields.reason}
            </span>
          )}
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
          <button className="button primary" disabled={busy}>
            {busy ? "Uploading and scanning…" : "Upload and scan"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
