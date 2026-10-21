"use client";
import { useState } from "react";
import { useTalentRead } from "./shared";
import type { Kind } from "./contracts";
import { labels } from "./contracts";
import {
  useFinancialCommand,
  downloadDocument,
  FinancialError,
} from "@/features/finance/shared";
type Notice = {
  id: string;
  kind: Kind;
  recordId: string;
  status: string;
  recordedAt: string;
};
export function TalentNotices({
  revision,
  onOpen,
}: {
  revision: number;
  onOpen: (kind: Kind, id: string) => void;
}) {
  const [page, setPage] = useState(0);
  const r = useTalentRead<Notice[]>(
    `/api/v1/talent/notifications?page=${page}`,
    revision,
  );
  return (
    <details className="time-panel">
      <summary>My talent workflow notifications</summary>
      <p>
        Committed in-app notices. Current record access is rechecked before a
        notice is displayed. No email delivery is asserted.
      </p>
      {!r ? (
        <p role="status">Loading notices…</p>
      ) : r.error ? (
        <p role="alert">{r.error}</p>
      ) : (
        <>
          {!r.data?.length && <p>No accessible notices on this page.</p>}
          {r.data?.map((n) => (
            <p key={n.id}>
              <button
                className="button secondary"
                onClick={() => onOpen(n.kind, n.recordId)}
              >
                {labels[n.kind]} · {n.status} ·{" "}
                {new Date(n.recordedAt).toLocaleString()}
              </button>
            </p>
          ))}
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous notices
          </button>
          <span>Page {page + 1}</span>
          <button
            disabled={page >= 10000}
            onClick={() => setPage((p) => p + 1)}
          >
            Next notices
          </button>
        </>
      )}
    </details>
  );
}
export function TalentExport({
  kind,
  employee,
  page,
}: {
  kind: Kind;
  employee: string;
  page: number;
}) {
  const c = useFinancialCommand();
  return (
    <div>
      <button
        className="button secondary"
        disabled={c.busy}
        onClick={async () => {
          const r = await c.run<{ filename: string; content: string }>(
            "/api/v1/talent/exports",
            { kind, employee, page },
          );
          if (r)
            downloadDocument(r.filename, r.content, "text/csv;charset=utf-8");
        }}
      >
        Export current page (up to 10 records)
      </button>
      <FinancialError message={c.error} fields={c.fields} />
    </div>
  );
}
