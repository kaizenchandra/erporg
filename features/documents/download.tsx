import { useEffect, useRef, useState } from "react";
import type { EmployeeDocument } from "./contracts";
export function DownloadButton({
  document: d,
  onDownloaded,
  label = "Download document",
}: {
  document: EmployeeDocument;
  onDownloaded?: () => void;
  label?: string;
}) {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  return (
    <>
      <button
        className="button secondary"
        disabled={busy}
        onClick={async () => {
          if (busy) return;
          const controller = new AbortController();
          request.current = controller;
          setBusy(true);
          setError("");
          try {
            const response = await fetch(`/api/v1/documents/${d.id}/content`, {
              cache: "no-store",
              signal: AbortSignal.any([
                controller.signal,
                AbortSignal.timeout(10000),
              ]),
            });
            if (!response.ok) {
              if (response.status === 401)
                window.dispatchEvent(new Event("atlas-session-expired"));
              throw new Error(
                "Download unavailable. Refresh documents or contact HR operations.",
              );
            }
            const blob = await response.blob(),
              url = URL.createObjectURL(blob);
            if (controller.signal.aborted) {
              URL.revokeObjectURL(url);
              return;
            }
            const link = document.createElement("a");
            link.href = url;
            link.download = d.filename;
            document.body.appendChild(link);
            link.click();
            link.remove();
            setTimeout(() => URL.revokeObjectURL(url), 1000);
            onDownloaded?.();
          } catch (e) {
            if (!controller.signal.aborted)
              setError(
                e instanceof Error
                  ? e.message
                  : "Download failed. Retry or contact HR operations.",
              );
          } finally {
            setBusy(false);
          }
        }}
      >
        {busy ? "Downloading…" : label}
      </button>
      {error && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </>
  );
}
