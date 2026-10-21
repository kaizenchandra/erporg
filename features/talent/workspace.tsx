"use client";
import { useEffect, useRef, useState } from "react";
import { TalentNotices, TalentExport } from "./controls";
import { TalentReminders } from "./reminders";
import { TalentApprovals, ResponsibilityHistory } from "./approvals";
import type { Actor } from "@/lib/contracts";
import { FinancialError } from "@/features/finance/shared";
import { Badge } from "@/components/ui/primitives";
import type {
  Audit,
  Context,
  Kind,
  RecordPage,
  TalentRecord,
  Requisition,
  Onboarding,
  Review,
  Course,
  Enrollment,
  Compensation,
  Succession,
  Scenario,
} from "./contracts";
import { labels, title } from "./contracts";
import { TalentPager, useTalentRead } from "./shared";
import {
  RequisitionEditor,
  RecruitmentDetail,
  JoiningDetail,
} from "./recruitment";
import {
  ReviewEditor,
  ReviewDetail,
  CourseEditor,
  CourseDetail,
  LearningDetail,
} from "./development";
import {
  CompensationEditor,
  CompensationDetail,
  SuccessionEditor,
  SuccessionDetail,
  ScenarioEditor,
  ScenarioDetail,
  PayrollIntakes,
} from "./planning";
export function TalentWorkspace({ actor }: { actor: Actor }) {
  const hr = actor.role === "HR";
  const [kind, setKind] = useState<Kind>(hr ? "requisitions" : "learning"),
    [revision, refresh] = useState(0),
    [page, setPage] = useState(0),
    [selected, setSelected] = useState<string | null>(null),
    [creating, setCreating] = useState(false),
    [scope, setScope] = useState(""),
    [search, setSearch] = useState(""),
    [workerPage, setWorkerPage] = useState(0),
    [notice, setNotice] = useState("");
  const context = useTalentRead<Context>(
      `/api/v1/talent/context?${new URLSearchParams({ search, page: String(workerPage) })}`,
      revision,
    ),
    list = useTalentRead<RecordPage>(
      `/api/v1/talent/${kind}?${new URLSearchParams({ page: String(page), employee: scope })}`,
      revision,
    );
  const tabs = (Object.keys(labels) as Kind[]).filter(
    (k) => hr || ["reviews", "courses", "learning", "onboarding"].includes(k),
  );
  function navigate(work: () => void) {
    if (
      window.dispatchEvent(
        new Event("atlas-before-navigation", { cancelable: true }),
      )
    )
      work();
  }
  function saved(r?: TalentRecord) {
    setCreating(false);
    if (r) setSelected(r.meta.id);
    setNotice(
      "Talent workflow committed. Showing authoritative persisted records.",
    );
    refresh((n) => n + 1);
  }
  const canCreate =
    (hr && !["onboarding", "learning"].includes(kind)) ||
    (actor.role === "MANAGER" && kind === "reviews");
  return (
    <section className="time-workspace" aria-label="Talent workspace">
      <div className="time-intro">
        <span className="eyebrow">GROWTH, WITH PURPOSE</span>
        <h2>Build your next chapter.</h2>
        <p>Connect hiring, joining, development and workforce decisions.</p>
        <small>
          Independent approvals and retained history. Compensation handoff is
          separate from payroll calculation and payment. No SAP, Workday or
          external LMS service is connected.
        </small>
      </div>
      {actor.role === "HR" && <TalentReminders revision={revision} />}
      {actor.role !== "EMPLOYEE" && (
        <TalentApprovals
          actor={actor}
          revision={revision}
          onOpen={(k, id) =>
            navigate(() => {
              setKind(k);
              setSelected(id);
              setCreating(false);
            })
          }
        />
      )}
      <TalentNotices
        revision={revision}
        onOpen={(k, id) =>
          navigate(() => {
            setKind(k);
            setSelected(id);
            setCreating(false);
          })
        }
      />
      <nav className="people-tabs" aria-label="Talent views">
        {tabs.map((k) => (
          <button
            key={k}
            className={kind === k ? "active" : ""}
            aria-pressed={kind === k}
            onClick={() =>
              navigate(() => {
                setKind(k);
                setPage(0);
                setSelected(null);
                setCreating(false);
                setScope("");
              })
            }
          >
            {labels[k]}
          </button>
        ))}
      </nav>
      {notice && <p role="status">{notice}</p>}
      <TalentExport kind={kind} employee={scope} page={page} />
      <div className="time-toolbar">
        <button
          className="button secondary"
          onClick={() => navigate(() => refresh((n) => n + 1))}
        >
          Refresh talent
        </button>
        {canCreate && (
          <button
            className="button primary"
            onClick={() =>
              navigate(() => {
                setCreating(true);
                setSelected(null);
              })
            }
          >
            Create {labels[kind].toLowerCase()}
          </button>
        )}
      </div>
      {!context ? (
        <p role="status">Loading authorised worker context…</p>
      ) : context.error ? (
        <FinancialError message={context.error} />
      ) : (
        <>
          <details className="time-panel">
            <summary>Worker search and selection scope</summary>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                navigate(() => {
                  const f = new FormData(e.currentTarget);
                  setSearch(String(f.get("search") ?? ""));
                  setWorkerPage(0);
                });
              }}
            >
              <label>
                Find a worker
                <input name="search" maxLength={100} defaultValue={search} />
              </label>
              <button className="button secondary">Search workers</button>
            </form>
            <p>
              {context.data?.workers.total} authorised workers · showing up to
              25 per page. Creation forms use this current search page.
            </p>
            <div className="time-pager">
              <button
                disabled={workerPage === 0}
                onClick={() => navigate(() => setWorkerPage((n) => n - 1))}
              >
                Previous workers
              </button>
              <span>Page {workerPage + 1}</span>
              <button
                disabled={
                  (workerPage + 1) * 25 >= (context.data?.workers.total ?? 0)
                }
                onClick={() => navigate(() => setWorkerPage((n) => n + 1))}
              >
                Next workers
              </button>
            </div>
          </details>
          {["reviews", "learning", "onboarding"].includes(kind) &&
            actor.role !== "EMPLOYEE" && (
              <label>
                Worker filter
                <select
                  value={scope}
                  onChange={(e) =>
                    navigate(() => {
                      setScope(e.target.value);
                      setPage(0);
                      setSelected(null);
                    })
                  }
                >
                  <option value="">
                    {hr ? "All authorised workers" : "My records"}
                  </option>
                  {context.data?.workers.items.map((w) => (
                    <option key={w.id} value={w.id}>
                      {w.name} · {w.id}
                    </option>
                  ))}
                </select>
              </label>
            )}
          {creating && context.data && (
            <section className="time-panel">
              <h3>Create {labels[kind].toLowerCase()}</h3>
              <button
                className="button secondary"
                onClick={() => navigate(() => setCreating(false))}
              >
                Cancel creation
              </button>
              <Editor kind={kind} context={context.data} onSaved={saved} />
            </section>
          )}
          {selected && context.data && (
            <Detail
              key={kind + selected}
              kind={kind}
              id={selected}
              context={context.data}
              revision={revision}
              onSaved={saved}
              onClose={() => navigate(() => setSelected(null))}
            />
          )}
        </>
      )}
      {!list ? (
        <p role="status">Loading talent records…</p>
      ) : list.error ? (
        <FinancialError message={list.error} />
      ) : (
        <section className="time-panel">
          <h3>{labels[kind]} register</h3>
          {!list.data?.items.length && (
            <p>
              No records in this authorised scope.{" "}
              {canCreate
                ? "Create a record to start the workflow."
                : "Your administrator or manager can assign a workflow."}
            </p>
          )}
          {list.data?.items.map((r) => (
            <article className="talent-subcard" key={r.meta.id}>
              <h4>{title(r)}</h4>
              <Badge status={r.meta.status} />
              <p>
                Revision {r.meta.version} ·{" "}
                {new Date(r.meta.recordedAt).toLocaleString()}
              </p>
              <button
                className="button secondary"
                aria-label={`Open ${title(r)}`}
                onClick={() =>
                  navigate(() => {
                    setSelected(r.meta.id);
                    setCreating(false);
                  })
                }
              >
                Open record
              </button>
            </article>
          ))}
          <TalentPager
            page={page}
            total={list.data?.total ?? 0}
            onPage={(n) => navigate(() => setPage(n))}
          />
        </section>
      )}
      {hr && kind === "compensation" && <PayrollIntakes revision={revision} />}
    </section>
  );
}
function Editor({
  kind,
  context,
  onSaved,
}: {
  kind: Kind;
  context: Context;
  onSaved: (r?: TalentRecord) => void;
}) {
  switch (kind) {
    case "requisitions":
      return <RequisitionEditor context={context} onSaved={onSaved} />;
    case "reviews":
      return <ReviewEditor context={context} onSaved={onSaved} />;
    case "courses":
      return <CourseEditor onSaved={onSaved} />;
    case "compensation":
      return <CompensationEditor context={context} onSaved={onSaved} />;
    case "succession":
      return <SuccessionEditor context={context} onSaved={onSaved} />;
    case "scenarios":
      return <ScenarioEditor context={context} onSaved={onSaved} />;
    default:
      return null;
  }
}
function Detail({
  kind,
  id,
  context,
  revision,
  onSaved,
  onClose,
}: {
  kind: Kind;
  id: string;
  context: Context;
  revision: number;
  onSaved: (r?: TalentRecord) => void;
  onClose: () => void;
}) {
  const r = useTalentRead<TalentRecord>(
    `/api/v1/talent/${kind}/${id}`,
    revision,
  );
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => {
    const previous = document.activeElement;
    return () => {
      if (previous instanceof HTMLElement && previous.isConnected)
        previous.focus();
    };
  }, []);
  useEffect(() => {
    if (r?.data) heading.current?.focus();
  }, [r?.data]);
  return (
    <section className="time-panel" aria-label="Talent record detail">
      <button className="button secondary" onClick={onClose}>
        Close record
      </button>
      {!r ? (
        <p role="status">Loading record…</p>
      ) : r.error ? (
        <FinancialError message={r.error} />
      ) : (
        r.data && (
          <>
            <h3 ref={heading} tabIndex={-1}>
              {title(r.data)}
            </h3>
            <Badge status={r.data.meta.status} />
            <p>
              Revision {r.data.meta.version} · prepared by{" "}
              {r.data.meta.preparedEmployee}
            </p>
            <p>
              Fields marked * are required. Save errors preserve entered values.
              Review actions apply to this record only.
            </p>
            {kind === "requisitions" ? (
              <RecruitmentDetail
                key={revision}
                r={r.data as Requisition}
                onSaved={onSaved}
              />
            ) : kind === "onboarding" ? (
              <JoiningDetail
                key={revision}
                r={r.data as Onboarding}
                context={context}
                onSaved={onSaved}
              />
            ) : kind === "reviews" ? (
              <ReviewDetail
                key={revision}
                r={r.data as Review}
                context={context}
                onSaved={onSaved}
              />
            ) : kind === "courses" ? (
              <CourseDetail
                key={revision}
                r={r.data as Course}
                context={context}
                onSaved={onSaved}
              />
            ) : kind === "learning" ? (
              <LearningDetail
                key={revision}
                r={r.data as Enrollment}
                context={context}
                onSaved={onSaved}
              />
            ) : kind === "compensation" ? (
              <CompensationDetail
                key={revision}
                r={r.data as Compensation}
                onSaved={onSaved}
              />
            ) : kind === "succession" ? (
              <SuccessionDetail
                r={r.data as Succession}
                context={context}
                onSaved={onSaved}
              />
            ) : (
              <ScenarioDetail r={r.data as Scenario} />
            )}
            {["reviews", "requisitions", "compensation"].includes(kind) && (
              <ResponsibilityHistory kind={kind} id={id} revision={revision} />
            )}
            <History kind={kind} id={id} revision={revision} />
          </>
        )
      )}
    </section>
  );
}
function History({
  kind,
  id,
  revision,
}: {
  kind: Kind;
  id: string;
  revision: number;
}) {
  const [page, setPage] = useState(0);
  const list = useTalentRead<Audit[]>(
    `/api/v1/talent/${kind}/${id}/history?page=${page}`,
    revision,
  );
  return (
    <details>
      <summary>Workflow history</summary>
      {!list ? (
        <p role="status">Loading history…</p>
      ) : list.error ? (
        <FinancialError message={list.error} />
      ) : (
        <>
          {list.data?.map((a) => (
            <article key={a.id} className="talent-subcard">
              <h4>
                {a.action} · {a.actor}
              </h4>
              <p>
                {a.reason} · {new Date(a.recordedAt).toLocaleString()}
              </p>
              <details>
                <summary>Recorded before and after</summary>
                <pre>{a.before || "New record"}</pre>
                <pre>{a.after}</pre>
              </details>
            </article>
          ))}
          <div className="time-pager">
            <button disabled={page === 0} onClick={() => setPage((n) => n - 1)}>
              Previous history
            </button>
            <span>Page {page + 1}</span>
            <button
              disabled={(list.data?.length ?? 0) < 20}
              onClick={() => setPage((n) => n + 1)}
            >
              Next history
            </button>
          </div>
        </>
      )}
    </details>
  );
}
