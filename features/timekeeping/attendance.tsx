"use client";
import { useId, useState } from "react";
import { Modal } from "@/components/ui/primitives";
import type { Actor } from "@/lib/contracts";
import { zonedTimestamp, type Page, type Shift } from "./contracts";
import {
  TimeError,
  TimePager,
  useTimeRead,
  useTimeCommand,
  useTimeDraft,
} from "./shared";
export function Attendance({ actor }: { actor: Actor }) {
  const [revision, refresh] = useState(0),
    [page, setPage] = useState(0),
    [zone, setZone] = useState("Etc/UTC"),
    [notice, setNotice] = useState("");
  const [edit, setEdit] = useState<Shift | "new" | null>(null);
  const result = useTimeRead<Page<Shift>>(
    `/api/v1/time/attendance/${encodeURIComponent(actor.employeeId)}?page=${page}`,
    revision,
  );
  const command = useTimeCommand();
  async function punch(action: string) {
    if (await command.run<Shift>(`/api/v1/time/punches/${action}`, { zone })) {
      setNotice(action === "IN" ? "Clock-in recorded." : "Clock-out recorded.");
      refresh((n) => n + 1);
      setPage(0);
    }
  }
  return (
    <section className="time-panel" aria-label="Attendance workspace">
      <div className="time-section-header">
        <div>
          <h2>Attendance</h2>
          <p>
            Record your shift. Correct missed punches with a reason; the
            original record stays in history.
          </p>
        </div>
        <button
          type="button"
          className="button secondary"
          onClick={() => refresh((n) => n + 1)}
        >
          Refresh attendance
        </button>
      </div>
      <div className="time-clock">
        <label>
          Attendance time zone
          <input
            value={zone}
            onChange={(e) => setZone(e.target.value)}
            placeholder="Europe/London"
          />
        </label>
        <button
          className="button primary"
          disabled={command.busy}
          onClick={() => punch("IN")}
        >
          Clock in
        </button>
        <button
          className="button secondary"
          disabled={command.busy}
          onClick={() => punch("OUT")}
        >
          Clock out
        </button>
        <button className="button secondary" onClick={() => setEdit("new")}>
          Record missed shift
        </button>
      </div>
      <TimeError message={command.error} fields={command.fields} />
      {notice && <p role="status">{notice}</p>}
      {!result ? (
        <p role="status">Loading attendance…</p>
      ) : result.error ? (
        <>
          <TimeError message={result.error} />
          <button onClick={() => refresh((n) => n + 1)}>
            Retry attendance
          </button>
        </>
      ) : (
        <>
          <div className="time-records">
            {result.data!.items.length === 0 && (
              <p>No attendance yet. Clock in or record a missed shift.</p>
            )}
            {result.data!.items.map((s) => (
              <article className="time-record" key={s.id}>
                <div>
                  <h3>
                    {new Intl.DateTimeFormat(undefined, {
                      dateStyle: "medium",
                      timeZone: s.zone,
                    }).format(new Date(s.start))}
                  </h3>
                  <p>
                    <time>{s.start}</time> →{" "}
                    <time>{s.end ?? "Missing clock-out · open shift"}</time>
                  </p>
                  <p>
                    {s.zone} · Revision {s.version}
                  </p>
                  <p>{s.reason}</p>
                </div>
                <button className="button secondary" onClick={() => setEdit(s)}>
                  Correct shift
                </button>
              </article>
            ))}
          </div>
          <TimePager page={page} total={result.data!.total} onPage={setPage} />
        </>
      )}
      {edit && (
        <CorrectionForm
          shift={edit === "new" ? null : edit}
          onClose={() => setEdit(null)}
          onSaved={() => {
            setEdit(null);
            setNotice("Attendance correction recorded with history.");
            refresh((n) => n + 1);
            setPage(0);
          }}
        />
      )}
    </section>
  );
}
function CorrectionForm({
  shift,
  onClose,
  onSaved,
}: {
  shift: Shift | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const id = useId(),
    c = useTimeCommand();
  const [start, setStart] = useState(
      shift ? zonedTimestamp(shift.start, shift.zone) : "",
    ),
    [end, setEnd] = useState(
      shift?.end ? zonedTimestamp(shift.end, shift.zone) : "",
    ),
    [zone, setZone] = useState(shift?.zone ?? "Etc/UTC"),
    [reason, setReason] = useState(""),
    [dirty, setDirty] = useState(false);
  useTimeDraft(dirty);
  const close = () => {
    if (
      !c.busy &&
      (!dirty || window.confirm("Discard this unsaved attendance correction?"))
    )
      onClose();
  };
  return (
    <Modal
      title={shift ? "Correct attendance" : "Record a missed shift"}
      onClose={close}
      closeDisabled={c.busy}
    >
      <form
        className="change-form"
        noValidate
        onChange={() => setDirty(true)}
        onSubmit={async (e) => {
          e.preventDefault();
          if (
            await c.run<Shift>("/api/v1/time/corrections", {
              id: shift?.id ?? null,
              version: shift?.version ?? 0,
              start,
              end,
              zone,
              reason,
            })
          ) {
            setDirty(false);
            onSaved();
          }
        }}
      >
        <p>
          Enter timestamps with explicit offsets, such as
          2026-10-20T09:00:00+01:00. Offsets must match the chosen IANA zone,
          including daylight-saving time. Maximum elapsed shift: 24 hours. This
          records a correction immediately; submitted timesheets have their own
          independent review.
        </p>
        <TimeError message={c.error} fields={c.fields} />
        {(
          [
            ["start", "Shift start", start, setStart],
            ["end", "Shift end", end, setEnd],
            ["zone", "Correction time zone", zone, setZone],
            ["reason", "Correction reason", reason, setReason],
          ] as const
        ).map(([field, label, value, set]) => (
          <label key={field} htmlFor={`${id}-${field}`}>
            {label} *
            <input
              id={`${id}-${field}`}
              value={value}
              required
              maxLength={field === "reason" ? 500 : 80}
              onChange={(e) => set(e.target.value)}
              aria-invalid={!!c.fields[field]}
              aria-describedby={
                c.fields[field] ? `${id}-${field}-error` : undefined
              }
            />
            {c.fields[field] && (
              <span className="error" id={`${id}-${field}-error`}>
                {c.fields[field]}
              </span>
            )}
          </label>
        ))}
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            disabled={c.busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={c.busy}>
            Save attendance correction
          </button>
        </div>
      </form>
    </Modal>
  );
}
