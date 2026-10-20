"use client";
import { useState } from "react";
import type { Actor } from "@/lib/contracts";
import { TimeDashboard } from "./dashboard";
import { TimeReports } from "./reports";
import { Periods } from "./periods";
import { Attendance } from "./attendance";
import { Timesheets, Evidence } from "./sheets";
export function TimeWorkspace({
  actor,
  inbox = false,
}: {
  actor: Actor;
  inbox?: boolean;
}) {
  const [tab, setTab] = useState(
    inbox ? (actor.role === "EMPLOYEE" ? "mine" : "review") : "attendance",
  );
  function go(next: string) {
    if (
      window.dispatchEvent(
        new Event("atlas-before-navigation", { cancelable: true }),
      )
    )
      setTab(next);
  }
  return (
    <div className="time-workspace">
      <div className="time-intro">
        <span className="eyebrow">WORKING TIME</span>
        <h2>Time, clearly accounted for.</h2>
        <p>
          Record attendance, submit your week, and follow each approval through
          to allocated hours.
        </p>
        <small>
          Local working-time policy: no statutory break, holiday or overtime
          rules configured.
        </small>
      </div>
      <nav className="people-tabs" aria-label="Time workspace views">
        {(!inbox
          ? [
              ["dashboard", "My week"],
              ["attendance", "Attendance"],
              ["mine", "My timesheets"],
              ...(actor.role !== "EMPLOYEE"
                ? [["review", "Team approvals"]]
                : []),
              ["history", "Time history"],
              ["periods", "Reporting periods"],
              ["reports", "Time reports"],
            ]
          : [
              ["mine", "My timesheets"],
              ...(actor.role !== "EMPLOYEE"
                ? [["review", "Team approvals"]]
                : []),
            ]
        ).map(([value, label]) => (
          <button
            key={value}
            aria-pressed={tab === value}
            className={tab === value ? "active" : ""}
            onClick={() => go(value)}
          >
            {label}
          </button>
        ))}
      </nav>
      {tab === "dashboard" ? (
        <TimeDashboard onNavigate={go} />
      ) : tab === "reports" ? (
        <TimeReports />
      ) : tab === "periods" ? (
        <Periods actor={actor} />
      ) : tab === "attendance" ? (
        <Attendance actor={actor} />
      ) : tab === "history" ? (
        <Evidence employeeId={actor.employeeId} />
      ) : (
        <Timesheets key={tab} actor={actor} review={tab === "review"} />
      )}
    </div>
  );
}
