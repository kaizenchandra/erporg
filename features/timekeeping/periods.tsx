"use client";
import { useState } from "react";
import type { Actor } from "@/lib/contracts";
import { monday } from "./sheet-editor";
import { TimeError, useTimeCommand, useTimeDraft, useTimeRead } from "./shared";
export type Period = {
  week: string;
  deadline: string;
  dailyLimit: number;
  weeklyLimit: number;
  recallAllowed: boolean;
  closed: boolean;
  version: number;
  reason: string;
  actor: string;
  recordedAt: string;
};
export type PeriodContext = { current: Period | null; history: Period[] };
export function PeriodSummary({ week }: { week: string }) {
  const r = useTimeRead<PeriodContext>(
    `/api/v1/time/periods/${encodeURIComponent(week)}`,
    0,
  );
  return !r ? (
    <p role="status">Loading reporting controls…</p>
  ) : r.error ? (
    <TimeError message={r.error} />
  ) : r.data!.current ? (
    <div className="time-record">
      <PeriodValues p={r.data!.current} />
    </div>
  ) : (
    <p>
      No controls configured for this week. Local policy: whole minutes, maximum
      1,440 per day; no submission deadline; pending submissions may be
      recalled. No statutory rules configured.
    </p>
  );
}
function PeriodValues({ p }: { p: Period }) {
  return (
    <div>
      <h3>
        Week of {p.week} · Revision {p.version}
      </h3>
      <p>
        {p.closed ? "Closed" : "Open"} · Submission deadline {p.deadline}
      </p>
      <p>
        Maximum {p.dailyLimit} minutes/day; {p.weeklyLimit} minutes/week. Recall{" "}
        {p.recallAllowed ? "permitted" : "disabled"}.
      </p>
      <p>
        {p.reason} · {p.actor} · {p.recordedAt}
      </p>
    </div>
  );
}
export function Periods({ actor }: { actor: Actor }) {
  const [week, setWeek] = useState(monday()),
    [revision, refresh] = useState(0),
    [notice, setNotice] = useState("");
  const r = useTimeRead<PeriodContext>(
    `/api/v1/time/periods/${encodeURIComponent(week)}`,
    revision,
  );
  return (
    <section className="time-panel" aria-label="Reporting period controls">
      <h2>Reporting periods</h2>
      {notice && <p role="status">{notice}</p>}
      <p>
        Weekly controls are organisation policy, not statutory payroll rules.
        Closing blocks saving, submission and approval; return, rejection and
        permitted recall remain available. A new revision preserves all
        historical controls. Existing approvals and allocations stay unchanged.
      </p>
      <label>
        Reporting week starting Monday
        <input
          type="date"
          value={week}
          onChange={(e) => {
            if (
              window.dispatchEvent(
                new Event("atlas-before-navigation", { cancelable: true }),
              )
            )
              setWeek(e.target.value);
          }}
        />
      </label>
      <button
        className="button secondary"
        onClick={() => {
          if (
            window.dispatchEvent(
              new Event("atlas-before-navigation", { cancelable: true }),
            )
          )
            refresh((n) => n + 1);
        }}
      >
        Refresh period controls
      </button>
      {!r ? (
        <p role="status">Loading period…</p>
      ) : r.error ? (
        <TimeError message={r.error} />
      ) : (
        <>
          {r.data!.current ? (
            <PeriodValues p={r.data!.current} />
          ) : (
            <p>No controls configured for this week. Local defaults apply.</p>
          )}
          {actor.role === "HR" && (
            <PeriodForm
              key={`${week}/${revision}/${r.data!.current?.version ?? 0}`}
              week={week}
              current={r.data!.current}
              onSaved={() => {
                setNotice("Period controls saved.");
                refresh((n) => n + 1);
              }}
            />
          )}
          <section aria-label="Period revision history">
            <h3>Control history</h3>
            {r.data!.history.map((p) => (
              <article className="time-record" key={p.version}>
                <PeriodValues p={p} />
              </article>
            ))}
            {!r.data!.history.length && <p>No recorded revisions.</p>}
          </section>
        </>
      )}
    </section>
  );
}
function PeriodForm({
  week,
  current,
  onSaved,
}: {
  week: string;
  current: Period | null;
  onSaved: () => void;
}) {
  const [deadline, setDeadline] = useState(
      current?.deadline ?? `${week}T23:59:00Z`,
    ),
    [daily, setDaily] = useState(String(current?.dailyLimit ?? 1440)),
    [weekly, setWeekly] = useState(String(current?.weeklyLimit ?? 10080)),
    [recall, setRecall] = useState(current?.recallAllowed ?? true),
    [closed, setClosed] = useState(current?.closed ?? false),
    [reason, setReason] = useState(""),
    [dirty, setDirty] = useState(false),
    [confirmed, setConfirmed] = useState(false);
  const c = useTimeCommand();
  useTimeDraft(dirty);
  return (
    <form
      className="change-form"
      noValidate
      onChange={() => {
        setDirty(true);
        setConfirmed(false);
      }}
      onSubmit={async (e) => {
        e.preventDefault();
        if (!confirmed) return;
        const p = await c.run<Period>("/api/v1/time/periods", {
          week,
          deadline,
          dailyLimit: Number(daily),
          weeklyLimit: Number(weekly),
          recallAllowed: recall,
          closed,
          version: current?.version ?? 0,
          reason,
        });
        if (p) {
          setDirty(false);
          onSaved();
        }
      }}
    >
      <h3>{current ? "Revise controls" : "Configure this week"}</h3>
      <TimeError message={c.error} fields={c.fields} />
      <label>
        Submission deadline (ISO timestamp with offset) *
        <input
          required
          aria-invalid={!!c.fields.deadline}
          value={deadline}
          onChange={(e) => setDeadline(e.target.value)}
        />
        {c.fields.deadline && (
          <span className="error">{c.fields.deadline}</span>
        )}
      </label>
      <label>
        Daily limit in minutes *
        <input
          required
          type="number"
          min="1"
          max="1440"
          step="1"
          aria-invalid={!!c.fields.dailyLimit || !!c.fields.minutes}
          value={daily}
          onChange={(e) => setDaily(e.target.value)}
        />
        {c.fields.dailyLimit && (
          <span className="error">{c.fields.dailyLimit}</span>
        )}
      </label>
      <label>
        Weekly limit in minutes *
        <input
          required
          type="number"
          min="1"
          max="10080"
          step="1"
          aria-invalid={!!c.fields.weeklyLimit}
          value={weekly}
          onChange={(e) => setWeekly(e.target.value)}
        />
        {c.fields.weeklyLimit && (
          <span className="error">{c.fields.weeklyLimit}</span>
        )}
      </label>
      <label className="removal-choice">
        <input
          type="checkbox"
          checked={recall}
          onChange={(e) => setRecall(e.target.checked)}
        />
        Allow worker recall before review
      </label>
      <label className="removal-choice">
        <input
          type="checkbox"
          checked={closed}
          onChange={(e) => setClosed(e.target.checked)}
        />
        Close reporting period
      </label>
      <label>
        Control change reason *
        <textarea
          required
          maxLength={500}
          aria-invalid={!!c.fields.reason}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
        />
        {c.fields.reason && <span className="error">{c.fields.reason}</span>}
      </label>
      <label className="removal-choice">
        <input
          type="checkbox"
          checked={confirmed}
          onChange={(e) => {
            e.stopPropagation();
            setConfirmed(e.target.checked);
          }}
        />
        I reviewed the effect on pending and draft timesheets.
      </label>
      <button className="button primary" disabled={c.busy || !confirmed}>
        Save period controls
      </button>
    </form>
  );
}
