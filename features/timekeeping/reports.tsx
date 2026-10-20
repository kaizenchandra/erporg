"use client";
import { useState } from "react";
import { api } from "@/lib/api";
import { Badge } from "@/components/ui/primitives";
import { hours } from "./contracts";
import { monday } from "./sheet-editor";
import { TimeError, TimePager, useTimeRead } from "./shared";
export type TimeReport = {
  items: {
    id: string;
    employeeId: string;
    week: string;
    status: string;
    version: number;
    totalMinutes: number;
    allocatedMinutes: number;
  }[];
  totalMinutes: number;
  allocatedMinutes: number;
  generatedAt: string;
};
export function TimeReports() {
  const [from, setFrom] = useState(() =>
      monday(new Date(Date.now() - 28 * 86400000)),
    ),
    [through, setThrough] = useState(monday()),
    [employeeId, setEmployee] = useState(""),
    [status, setStatus] = useState("ALL"),
    [page, setPage] = useState(0),
    [query, setQuery] = useState({ from, through, employeeId, status }),
    [revision, refresh] = useState(0),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState("");
  const result = useTimeRead<TimeReport>(
    `/api/v1/time/reports?${new URLSearchParams(query)}`,
    revision,
  );
  return (
    <section className="time-panel" aria-label="Time reports">
      <h2>Time reports & exports</h2>
      <p>
        Your own time and currently authorised team records. Filters select
        reporting weeks, not individual work dates. Monetary costs, overtime and
        payroll or billing processing statuses are not included in this report.
      </p>
      <form
        className="change-form"
        onSubmit={(e) => {
          e.preventDefault();
          setQuery({ from, through, employeeId, status });
          setPage(0);
          setNotice("");
          refresh((n) => n + 1);
        }}
      >
        <div className="time-form-grid">
          <label>
            First reporting Monday
            <input
              type="date"
              required
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label>
            Last reporting Monday
            <input
              type="date"
              required
              value={through}
              onChange={(e) => setThrough(e.target.value)}
            />
          </label>
          <label>
            Worker ID (optional)
            <input
              maxLength={40}
              value={employeeId}
              onChange={(e) => setEmployee(e.target.value)}
            />
          </label>
          <label>
            Report status
            <select value={status} onChange={(e) => setStatus(e.target.value)}>
              {[
                "ALL",
                "DRAFT",
                "SUBMITTED",
                "APPROVED",
                "RETURNED",
                "CORRECTED",
                "REJECTED",
              ].map((s) => (
                <option key={s}>{s}</option>
              ))}
            </select>
          </label>
        </div>
        <button className="button primary" disabled={busy}>
          Apply report filters
        </button>
      </form>
      <TimeError message={error || result?.error} />
      {notice && <p role="status">{notice}</p>}
      {!result ? (
        <p role="status">Loading authorised time report…</p>
      ) : (
        result.data && (
          <>
            <p role="status">
              {result.data.items.length} timesheets · Working time{" "}
              {hours(result.data.totalMinutes)} · Posted hours{" "}
              {hours(result.data.allocatedMinutes)} across all matching records.
            </p>
            <p>
              Generated {result.data.generatedAt}. Export rechecks permissions
              and current records. Maximum 1,000 sheets across 53 reporting
              weeks; narrow filters if the limit is exceeded.
            </p>
            <button
              type="button"
              className="button secondary"
              disabled={busy}
              onClick={async () => {
                if (busy) return;
                setBusy(true);
                setError("");
                setNotice("");
                try {
                  const download = await api<{
                    filename: string;
                    content: string;
                    rows: number;
                  }>("/api/v1/time/reports/export", {
                    method: "POST",
                    body: JSON.stringify(query),
                  });
                  const url = URL.createObjectURL(
                    new Blob([download.content], {
                      type: "text/csv;charset=utf-8",
                    }),
                  );
                  const a = document.createElement("a");
                  a.href = url;
                  a.download = download.filename;
                  a.click();
                  URL.revokeObjectURL(url);
                  setNotice(
                    `Exported ${download.rows} authorised timesheets. Download recorded in time audit history.`,
                  );
                } catch (e: unknown) {
                  setError(
                    e instanceof Error
                      ? e.message
                      : "Export failed. Retry with the same filters.",
                  );
                } finally {
                  setBusy(false);
                }
              }}
            >
              Export all filtered timesheets (CSV)
            </button>
            {!result.data.items.length && (
              <p>No timesheets match these filters in your authorised scope.</p>
            )}
            <div className="time-records">
              {result.data.items.slice(page * 10, (page + 1) * 10).map((s) => (
                <article className="time-record" key={s.id}>
                  <div>
                    <h3>
                      {s.employeeId} · Week of {s.week}
                    </h3>
                    <Badge status={s.status} />
                    <p>
                      Working {hours(s.totalMinutes)} · Allocated{" "}
                      {hours(s.allocatedMinutes)} · Revision {s.version}
                    </p>
                  </div>
                </article>
              ))}
            </div>
            <TimePager
              page={page}
              total={result.data.items.length}
              onPage={setPage}
            />
          </>
        )
      )}
    </section>
  );
}
