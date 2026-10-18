"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { formatDate } from "@/components/ui/primitives";
export type CaseEpisode = {
  requestId: string;
  employeeId: string;
  episodeNumber: number;
  episodeStartDate: string;
  sourceRecordedAt: string;
  linkedAt: string;
  origin: "COMMAND" | "COMPAT_COMMAND" | "MIGRATED_V42";
  linkedBy: string;
  correlationId: string;
};
export function CaseEpisodePanel({ requestId }: { requestId: string }) {
  const [data, setData] = useState<CaseEpisode | null>(null),
    [error, setError] = useState(""),
    [revision, setRevision] = useState(0);
  const summary = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const c = new AbortController();
    api<CaseEpisode>(`/api/v1/resignations/${requestId}/episode`, {
      signal: c.signal,
    })
      .then((r) => {
        if (!c.signal.aborted) setData(r);
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setError(
            e instanceof Error
              ? e.message
              : "Unable to load episode ownership.",
          );
      });
    return () => c.abort();
  }, [requestId, revision]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  return (
    <section aria-label="Case employment episode">
      <h3>Employment episode</h3>
      {error ? (
        <div role="alert" tabIndex={-1} ref={summary} className="error">
          <p>{error}</p>
          <button
            type="button"
            className="button secondary"
            onClick={() => {
              setError("");
              setData(null);
              setRevision((v) => v + 1);
            }}
          >
            Retry episode ownership
          </button>
        </div>
      ) : !data ? (
        <p role="status">Loading episode ownership…</p>
      ) : (
        <>
          <dl className="review-details">
            <div>
              <dt>Owning episode</dt>
              <dd>Original employment · {data.episodeNumber}</dd>
            </div>
            <div>
              <dt>Episode start snapshot</dt>
              <dd>{formatDate(data.episodeStartDate)}</dd>
            </div>
            <div>
              <dt>Submission recorded</dt>
              <dd>{new Date(data.sourceRecordedAt).toLocaleString()}</dd>
            </div>
          </dl>
          <p>
            This request stays linked to this episode when the employee returns
            for a later employment period.
          </p>
          <details>
            <summary>Episode link history</summary>
            <p>
              {data.origin === "MIGRATED_V42"
                ? "Linked from pre-existing records under the original employment workflow. The link was captured later; the submission timestamp is unchanged."
                : "Episode ownership was captured with this submission."}
            </p>
            <p>
              Linked {new Date(data.linkedAt).toLocaleString()} ·{" "}
              {data.linkedBy}
            </p>
            <p>Correlation: {data.correlationId}</p>
          </details>
        </>
      )}
    </section>
  );
}
