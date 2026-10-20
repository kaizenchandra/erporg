"use client";
import { useState } from "react";
import { Badge } from "@/components/ui/primitives";
import type { Sheet } from "./contracts";
import type { Period } from "./periods";
import { hours } from "./contracts";
import { monday } from "./sheet-editor";
import { TimeError, useTimeRead } from "./shared";
type Dashboard = {
  week: string;
  sheet: Sheet | null;
  period: Period | null;
  pendingApprovals: number;
  canReview: boolean;
  generatedAt: string;
};
export function TimeDashboard({
  onNavigate,
}: {
  onNavigate: (tab: string) => void;
}) {
  const [week, setWeek] = useState(monday()),
    [revision, refresh] = useState(0);
  const result = useTimeRead<Dashboard>(
      `/api/v1/time/dashboard?week=${encodeURIComponent(week)}`,
      revision,
    ),
    d = result?.data;
  return (
    <section className="time-panel" aria-label="Working time dashboard">
      <h2>Your working week</h2>
      <div className="time-clock">
        <label>
          Dashboard reporting Monday
          <input
            type="date"
            value={week}
            onChange={(e) => setWeek(e.target.value)}
          />
        </label>
        <button
          className="button secondary"
          onClick={() => refresh((n) => n + 1)}
        >
          Refresh dashboard
        </button>
      </div>
      <TimeError message={result?.error} />
      {!result ? (
        <p role="status">Loading your weekly status…</p>
      ) : (
        d && (
          <>
            <div className="time-records">
              <article className="time-record">
                <div>
                  <h3>Your timesheet</h3>
                  {d.sheet ? (
                    <>
                      <Badge status={d.sheet.status} />
                      <p>
                        {hours(d.sheet.totalMinutes)} recorded · Revision{" "}
                        {d.sheet.version}
                      </p>
                      {d.sheet.comment && <p>Reviewer: {d.sheet.comment}</p>}
                    </>
                  ) : (
                    <p>
                      No timesheet recorded for this week and current employment
                      episode.
                    </p>
                  )}
                  <button
                    className="button secondary"
                    onClick={() => onNavigate("mine")}
                  >
                    Go to my timesheets
                  </button>
                </div>
              </article>
              <article className="time-record">
                <div>
                  <h3>Reporting period</h3>
                  <p>
                    {d.period?.closed ? "Closed" : "Open"} ·{" "}
                    {d.period
                      ? `Policy revision ${d.period.version}`
                      : "Default local policy; no period-specific controls configured"}
                  </p>
                  <p>
                    {d.period?.deadline
                      ? `Submission deadline ${new Date(d.period.deadline).toLocaleString()}`
                      : "No submission deadline configured."}
                  </p>
                  <button
                    className="button secondary"
                    onClick={() => onNavigate("periods")}
                  >
                    View period controls
                  </button>
                </div>
              </article>
              {d.canReview && (
                <article className="time-record">
                  <div>
                    <h3>Pending team approvals</h3>
                    <p>
                      {d.pendingApprovals} submitted timesheets across all
                      reporting weeks in your current review scope.
                    </p>
                    <button
                      className="button secondary"
                      onClick={() => onNavigate("review")}
                    >
                      Review team submissions
                    </button>
                  </div>
                </article>
              )}
            </div>
            <section aria-label="Time alerts">
              <h3>Time alerts</h3>
              {!d.sheet && (
                <p>
                  No draft exists for the selected week. Check whether time
                  reporting is required before creating one.
                </p>
              )}
              {d.sheet && ["RETURNED", "REJECTED"].includes(d.sheet.status) && (
                <p>
                  Your timesheet needs attention. Read the review reason,
                  correct the draft, and resubmit.
                </p>
              )}
              {d.period?.closed && (
                <p>
                  This period is closed. Saving, submitting and approving are
                  restricted.
                </p>
              )}
              {d.period?.deadline &&
                Date.parse(d.period.deadline) < Date.parse(d.generatedAt) && (
                  <p>
                    The configured submission deadline has passed. Contact HR if
                    an authorised policy change is required.
                  </p>
                )}
              <p>
                Scheduled hours, absence and holiday rules are not configured
                for timekeeping. Missing expected hours cannot yet be
                calculated. Payroll and billing processing statuses and
                delivered notifications are not displayed in this workspace.
              </p>
            </section>
            <p>
              Updated {d.generatedAt}. Refresh to obtain current workflow and
              period status.
            </p>
          </>
        )
      )}
    </section>
  );
}
