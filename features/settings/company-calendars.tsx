"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { formatDate } from "@/components/ui/primitives";
import type { CalendarPage } from "./calendar-contracts";
import { weekdays } from "./calendar-contracts";
import { CreateCompanyCalendar } from "./create-company-calendar";
import { CompanyCalendarDetail } from "./company-calendar-detail";
import { RetireCompanyCalendar } from "./retire-company-calendar";
import { AmendCompanyCalendar } from "./amend-company-calendar";
export function CompanyCalendars({ actor }: { actor: Actor }) {
  const [draft, setDraft] = useState(""),
    [search, setSearch] = useState(""),
    [status, setStatus] = useState("ALL"),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [creating, setCreating] = useState(false),
    [detail, setDetail] = useState<string | null>(null),
    [notice, setNotice] = useState("");
  const [amending, setAmending] = useState(false);
  const [retiring, setRetiring] = useState(false);
  const [amendmentNotice, setAmendmentNotice] = useState("");
  const [result, setResult] = useState<{
    key: string;
    data?: CalendarPage;
    zones?: string[];
    error?: string;
  }>();
  const errorRef = useRef<HTMLParagraphElement>(null),
    createRef = useRef<HTMLButtonElement>(null),
    heading = useRef<HTMLHeadingElement>(null),
    returnTo = useRef<string | null>(null);
  const key = JSON.stringify([
    actor.tenant,
    actor.subject,
    actor.employeeId,
    actor.role,
    search,
    status,
    page,
    revision,
  ]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    if (actor.role !== "HR") return;
    const c = new AbortController();
    const options = {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    };
    const query = new URLSearchParams({
      search,
      status,
      page: String(page),
      size: "10",
    });
    Promise.all([
      api<CalendarPage>(`/api/v1/company-calendars?${query}`, options),
      api<string[]>("/api/v1/company-calendars/time-zones", options),
    ])
      .then(([data, zones]) => {
        if (!c.signal.aborted) setResult({ key, data, zones });
      })
      .catch(() => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              "Company calendars could not be loaded. Retry to refresh authorised data.",
          });
      });
    return () => c.abort();
  }, [key, actor.role, search, status, page]);
  useEffect(() => {
    if (current?.error) errorRef.current?.focus();
    else if (current?.data && returnTo.current) {
      const target = returnTo.current;
      returnTo.current = null;
      if (target === "create") createRef.current?.focus();
      else
        (
          document.getElementById(`calendar-${target}`) ?? heading.current
        )?.focus();
    }
  }, [current]);
  if (actor.role !== "HR") return null;
  return (
    <section
      className="department-reference legal-entity-settings"
      aria-label="Company calendars"
    >
      <div className="section-heading">
        <h2 ref={heading} tabIndex={-1}>
          Company calendars
        </h2>
        <span>HR configuration</span>
      </div>
      <p>
        Record working weekdays and explicit company closures. Calendars do not
        assign employees or activate leave, public-holiday or payroll rules.
        Cards show original creation policy; inspect dated amendments for later
        changes.
      </p>
      {notice && (
        <p role="status" className="success">
          {notice}
        </p>
      )}
      <form
        role="search"
        aria-label="Search company calendars"
        className="inbox-search"
        onSubmit={(e) => {
          e.preventDefault();
          setSearch(draft);
          setPage(0);
        }}
      >
        <label>
          Calendar code or name
          <input
            value={draft}
            maxLength={120}
            onChange={(e) => setDraft(e.target.value)}
          />
        </label>
        <button className="button secondary">Search calendars</button>
      </form>
      <label>
        Calendar availability
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          {["ALL", "CURRENT", "PLANNED", "EXPIRED", "RETIRED"].map((v) => (
            <option key={v} value={v}>
              {v[0] + v.slice(1).toLowerCase()}
            </option>
          ))}
        </select>
      </label>
      <div className="form-footer">
        <button
          ref={createRef}
          className="button primary"
          disabled={!current?.zones}
          onClick={() => setCreating(true)}
        >
          Create company calendar
        </button>
        <button
          className="button secondary"
          onClick={() => setRevision((n) => n + 1)}
        >
          Refresh calendars
        </button>
      </div>
      {!current ? (
        <p role="status">Loading company calendars…</p>
      ) : current.error ? (
        <p role="alert" tabIndex={-1} ref={errorRef}>
          {current.error}{" "}
          <button
            className="button secondary"
            onClick={() => setRevision((n) => n + 1)}
          >
            Retry company calendars
          </button>
        </p>
      ) : (
        current.data && (
          <>
            {current.data.items.length === 0 ? (
              <p>
                No company calendars match these filters. Change the filters or
                create a calendar.
              </p>
            ) : (
              <div className="detail-grid">
                {current.data.items.map((e) => (
                  <article className="benefit-card" key={e.calendar.code}>
                    <h3>{e.calendar.name}</h3>
                    <p>
                      {e.calendar.code} · {e.calendar.timeZone}
                    </p>
                    <p>
                      {formatDate(e.calendar.effectiveFrom)} –{" "}
                      {formatDate(e.calendar.effectiveUntil)}
                    </p>
                    <p>
                      {e.calendar.workingDays
                        .map((d) => weekdays[d - 1])
                        .join(", ")}{" "}
                      · {e.calendar.closedDates.length} original company
                      closures
                    </p>
                    {current.data!.retirementDates[e.calendar.code] && (
                      <p>
                        Retirement date:{" "}
                        {formatDate(
                          current.data!.retirementDates[e.calendar.code],
                        )}
                      </p>
                    )}
                    <button
                      id={`calendar-${e.calendar.code}`}
                      className="button secondary"
                      onClick={() => {
                        setAmendmentNotice("");
                        setDetail(e.calendar.code);
                      }}
                    >
                      Inspect calendar · {e.calendar.code}
                    </button>
                  </article>
                ))}
              </div>
            )}
            <p className="muted">
              {current.data.total} calendars · page {page + 1} · ordered by
              code. No bulk action applies.
            </p>
            <div className="form-footer">
              <button
                className="button secondary"
                disabled={!page}
                onClick={() => setPage((p) => p - 1)}
              >
                Previous calendars
              </button>
              <button
                className="button secondary"
                disabled={(page + 1) * 10 >= current.data!.total}
                onClick={() => setPage((p) => p + 1)}
              >
                Next calendars
              </button>
            </div>
          </>
        )
      )}
      {creating && current?.zones && (
        <CreateCompanyCalendar
          zones={current.zones}
          onClose={() => {
            setCreating(false);
            returnTo.current = "create";
            setRevision((n) => n + 1);
          }}
          onSaved={(e) => {
            setNotice(
              `${e.calendar.code} recorded. No employee assignments or leave rules changed.`,
            );
            setSearch(e.calendar.code);
            setDraft(e.calendar.code);
            setStatus("ALL");
            setPage(0);
            setCreating(false);
            returnTo.current = "create";
            setRevision((n) => n + 1);
          }}
        />
      )}
      {detail &&
        (retiring ? (
          <RetireCompanyCalendar
            code={detail}
            onClose={() => setRetiring(false)}
            onSaved={() => {
              setAmendmentNotice(`Retirement recorded for ${detail}.`);
              setRetiring(false);
            }}
          />
        ) : amending ? (
          <AmendCompanyCalendar
            code={detail}
            onClose={() => setAmending(false)}
            onSaved={(e) => {
              setAmendmentNotice(
                `Revision ${e.revision.version} recorded for ${detail}.`,
              );
              setAmending(false);
            }}
          />
        ) : (
          <CompanyCalendarDetail
            onAmend={() => setAmending(true)}
            onRetire={() => setRetiring(true)}
            notice={amendmentNotice}
            code={detail}
            onClose={() => {
              returnTo.current = detail;
              setDetail(null);
              setRevision((n) => n + 1);
            }}
          />
        ))}
    </section>
  );
}
