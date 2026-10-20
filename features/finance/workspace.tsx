"use client";
import { useState } from "react";
import type { Actor } from "@/lib/contracts";
import { Badge } from "@/components/ui/primitives";
import {
  amount,
  type Context,
  type Journal,
  type Page,
  type Ledger,
  type TrialBalance,
  type Period,
  type Audit,
} from "./contracts";
import {
  FinancialError,
  FinancialPager,
  useFinancialRead,
  useFinancialCommand,
  useFinancialDraft,
} from "./shared";
import { JournalEditor } from "./journal-editor";
import { JournalDetail } from "./journal-detail";
export function FinanceWorkspace({ actor }: { actor: Actor }) {
  const [revision, refresh] = useState(0),
    [entity, setEntity] = useState("DEMO-FIN"),
    [tab, setTab] = useState("journals"),
    [page, setPage] = useState(0),
    [status, setStatus] = useState("ALL"),
    [selected, setSelected] = useState<string | null>(null),
    [edit, setEdit] = useState<Journal | "new" | null>(null),
    [notice, setNotice] = useState("");
  const context = useFinancialRead<Context>(
      "/api/v1/finance/context",
      revision,
    ),
    list = useFinancialRead<Page<Journal>>(
      `/api/v1/finance/journals?${new URLSearchParams({ entity, status, page: String(page) })}`,
      revision,
    ),
    grants = context?.data?.grants ?? [],
    ledger = context?.data?.ledgers.find((l) => l.entity === entity),
    has = (p: string) =>
      grants.some((g) => g.entity === entity && g.permission === p);
  function navigate(next: string) {
    if (
      window.dispatchEvent(
        new Event("atlas-before-navigation", { cancelable: true }),
      )
    )
      setTab(next);
  }
  const saved = () => {
    setSelected(null);
    setEdit(null);
    setNotice(
      "Journal command committed. Refreshing authoritative ledger state.",
    );
    refresh((n) => n + 1);
  };
  return (
    <section className="time-workspace" aria-label="Finance workspace">
      <div className="time-intro">
        <span className="eyebrow">ACCOUNTING CONTROL</span>
        <h2>Every entry, accounted for.</h2>
        <p>
          Independent preparation, approval and posting with traceable sources.
        </p>
        <small>
          Demonstration USD ledger. Banks, subledgers, statutory tax,
          consolidation and production accounting policy are not configured.
        </small>
      </div>
      {!context ? (
        <p role="status">Loading financial access…</p>
      ) : context.error ? (
        <FinancialError message={context.error} />
      ) : grants.length === 0 ? (
        <p role="alert">
          No financial access is assigned to your current identity. HR and
          manager roles do not automatically grant finance permissions.
        </p>
      ) : (
        <>
          <label>
            Finance legal entity
            <select
              value={entity}
              onChange={(e) => {
                if (
                  window.dispatchEvent(
                    new Event("atlas-before-navigation", { cancelable: true }),
                  )
                ) {
                  setEntity(e.target.value);
                  setPage(0);
                  setSelected(null);
                }
              }}
            >
              {Array.from(new Set(grants.map((g) => g.entity))).map((e) => (
                <option key={e}>{e}</option>
              ))}
            </select>
          </label>
          <nav className="people-tabs" aria-label="Finance views">
            {[
              ["journals", "Journals"],
              ["balances", "Trial balance"],
              ["periods", "Posting periods"],
              ["audit", "Financial history"],
              ["master", "Ledger configuration"],
            ].map(([value, label]) => (
              <button
                key={value}
                aria-pressed={tab === value}
                className={tab === value ? "active" : ""}
                onClick={() => navigate(value)}
              >
                {label}
              </button>
            ))}
          </nav>
          {notice && <p role="status">{notice}</p>}
          {tab === "master" ? (
            <>
              {ledger ? (
                <section className="time-panel">
                  <h3>
                    {ledger.entity} · {ledger.currency}
                  </h3>
                  <p>
                    Effective {ledger.effectiveFrom}. Cost centres:{" "}
                    {ledger.costCentres.join(", ")}
                  </p>
                  {ledger.accounts.map((a) => (
                    <p key={a.code}>
                      {a.code} · {a.name} · {a.kind}
                      {a.requiresCostCentre ? " · Cost centre required" : ""}
                    </p>
                  ))}
                  <p>
                    This fixed demonstration chart cannot be amended here. Full
                    effective-dated account and dimension administration remains
                    incomplete.
                  </p>
                </section>
              ) : has("SETUP") ? (
                <LedgerSetup
                  key={entity}
                  entity={entity}
                  onSaved={() => refresh((n) => n + 1)}
                />
              ) : (
                <p>
                  Ledger not configured. An authorised setup user must establish
                  the existing Core HR legal entity and demonstration chart.
                </p>
              )}
            </>
          ) : tab === "balances" ? (
            <TrialBalancePanel entity={entity} revision={revision} />
          ) : tab === "periods" ? (
            <PeriodPanel
              entity={entity}
              canSetup={has("SETUP")}
              revision={revision}
              onSaved={() => refresh((n) => n + 1)}
            />
          ) : tab === "audit" ? (
            <HistoryPanel entity={entity} revision={revision} />
          ) : (
            <section className="time-panel">
              <div className="time-section-header">
                <h3>Journal register</h3>
                {has("PREPARE") && ledger && (
                  <button
                    className="button primary"
                    onClick={() => setEdit("new")}
                  >
                    Prepare manual journal
                  </button>
                )}
              </div>
              <div className="time-clock">
                <label>
                  Journal status
                  <select
                    value={status}
                    onChange={(e) => {
                      setStatus(e.target.value);
                      setPage(0);
                    }}
                  >
                    {[
                      "ALL",
                      "DRAFT",
                      "SUBMITTED",
                      "APPROVED",
                      "POSTED",
                      "REVERSED",
                      "RETURNED",
                      "REJECTED",
                    ].map((x) => (
                      <option key={x}>{x}</option>
                    ))}
                  </select>
                </label>
                <button
                  className="button secondary"
                  onClick={() => refresh((n) => n + 1)}
                >
                  Refresh journals
                </button>
              </div>
              {!list ? (
                <p role="status">Loading journals…</p>
              ) : list.error ? (
                <FinancialError message={list.error} />
              ) : (
                <>
                  <div className="time-records">
                    {!list.data!.items.length && (
                      <p>No journals match this entity and status.</p>
                    )}
                    {list.data!.items.map((j) => (
                      <article className="time-record" key={j.id}>
                        <div>
                          <h4>
                            {j.postingDate} · {j.sourceType}
                          </h4>
                          <Badge status={j.status} />
                          <p>
                            {j.id} · {j.currency} · Revision {j.version}
                          </p>
                          <p>{j.reason}</p>
                        </div>
                        <button
                          className="button secondary"
                          aria-label={`Review journal ${j.id}`}
                          onClick={() => setSelected(j.id)}
                        >
                          Review journal
                        </button>
                      </article>
                    ))}
                  </div>
                  <FinancialPager
                    page={page}
                    total={list.data!.total}
                    onPage={setPage}
                  />
                </>
              )}
            </section>
          )}
          {selected && (
            <JournalDetail
              key={selected}
              id={selected}
              actor={actor}
              grants={grants}
              onClose={() => setSelected(null)}
              onSaved={saved}
              onEdit={(j) => {
                setSelected(null);
                setEdit(j);
              }}
            />
          )}
          {edit && ledger && (
            <JournalEditor
              ledger={ledger}
              journal={edit === "new" ? null : edit}
              onClose={() => setEdit(null)}
              onSaved={saved}
            />
          )}
        </>
      )}
    </section>
  );
}
function LedgerSetup({
  entity,
  onSaved,
}: {
  entity: string;
  onSaved: () => void;
}) {
  const [centres, setCentres] = useState("ENGINEERING"),
    [reason, setReason] = useState(""),
    [confirm, setConfirm] = useState(false),
    c = useFinancialCommand();
  useFinancialDraft(reason.length > 0);
  return (
    <form
      className="change-form"
      noValidate
      onSubmit={async (e) => {
        e.preventDefault();
        if (!confirm) return;
        const l = await c.run<Ledger>("/api/v1/finance/ledgers", {
          entity,
          costCentres: centres.split(",").map((x) => x.trim()),
          reason,
        });
        if (l) onSaved();
      }}
    >
      <h3>Configure demonstration ledger</h3>
      <p>
        Create legal entity {entity} in Settings first, using USD. This creates
        a fixed test chart of wage expense, voluntary deductions payable, net
        pay payable and bank accounts. It establishes no statutory or production
        accounting policy.
      </p>
      <FinancialError message={c.error} fields={c.fields} />
      <label>
        Cost centre codes (comma separated)
        <input value={centres} onChange={(e) => setCentres(e.target.value)} />
      </label>
      <label>
        Ledger configuration reason
        <textarea
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
        />
      </label>
      <label className="removal-choice">
        <input
          type="checkbox"
          checked={confirm}
          onChange={(e) => setConfirm(e.target.checked)}
        />
        I am configuring synthetic demonstration records.
      </label>
      <button className="button primary" disabled={c.busy || !confirm}>
        Create demonstration ledger
      </button>
    </form>
  );
}
function TrialBalancePanel({
  entity,
  revision,
}: {
  entity: string;
  revision: number;
}) {
  const [from, setFrom] = useState(
      () => new Date().toISOString().slice(0, 7) + "-01",
    ),
    [through, setThrough] = useState(() =>
      new Date().toISOString().slice(0, 10),
    );
  const result = useFinancialRead<TrialBalance>(
    `/api/v1/finance/trial-balance?${new URLSearchParams({ entity, from, through })}`,
    revision,
  );
  return (
    <section className="time-panel" aria-label="Trial balance">
      <h3>Trial balance</h3>
      <p>
        Persisted posted and reversed journal history, including the offsetting
        reversal entry. Drafts and approvals are excluded. This is not a
        complete financial statement package.
      </p>
      <div className="time-form-grid">
        <label>
          Balance from
          <input
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </label>
        <label>
          Balance through
          <input
            type="date"
            value={through}
            onChange={(e) => setThrough(e.target.value)}
          />
        </label>
      </div>
      {!result ? (
        <p role="status">Loading posted balances…</p>
      ) : result.error ? (
        <FinancialError message={result.error} />
      ) : (
        <>
          <p role="status">
            {result.data!.currency} · Debits {amount(result.data!.debit)} ·
            Credits {amount(result.data!.credit)}
          </p>
          {!result.data!.items.length && (
            <p>No posted transactions in this range.</p>
          )}
          {result.data!.items.map((r, i) => (
            <article className="time-record" key={i}>
              <div>
                <h4>
                  {r.account} · {r.costCentre || "No dimension"}
                </h4>
                <p>
                  Debit {amount(r.debit)} · Credit {amount(r.credit)}
                </p>
              </div>
            </article>
          ))}
        </>
      )}
    </section>
  );
}
function PeriodPanel({
  entity,
  revision,
  canSetup,
  onSaved,
}: {
  entity: string;
  revision: number;
  canSetup: boolean;
  onSaved: () => void;
}) {
  const [month, setMonth] = useState(() =>
      new Date().toISOString().slice(0, 7),
    ),
    [closed, setClosed] = useState(true),
    [reason, setReason] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    c = useFinancialCommand();
  useFinancialDraft(reason.length > 0);
  const result = useFinancialRead<{ current: Period | null }>(
    `/api/v1/finance/period?${new URLSearchParams({ entity, month: month + "-01" })}`,
    revision,
  );
  return (
    <section className="time-panel">
      <h3>Posting period controls</h3>
      <label>
        Fiscal month
        <input
          type="month"
          value={month}
          onChange={(e) => {
            setMonth(e.target.value);
            setConfirmed(false);
          }}
        />
      </label>
      {!result ? (
        <p role="status">Loading period…</p>
      ) : result.error ? (
        <FinancialError message={result.error} />
      ) : (
        <>
          <p role="status">
            {result.data?.current?.closed ? "Closed" : "Open"} · Revision{" "}
            {result.data?.current?.version ?? 0}
          </p>
          {result.data?.current && (
            <p>
              {result.data.current.reason} · {result.data.current.actor} ·{" "}
              {result.data.current.recordedAt}
            </p>
          )}
          {canSetup && (
            <form
              noValidate
              onSubmit={async (e) => {
                e.preventDefault();
                if (!confirmed) return;
                const p = await c.run<Period>("/api/v1/finance/periods", {
                  entity,
                  month: month + "-01",
                  closed,
                  version: result.data?.current?.version ?? 0,
                  reason,
                });
                if (p) {
                  setReason("");
                  setConfirmed(false);
                  onSaved();
                }
              }}
            >
              <FinancialError message={c.error} fields={c.fields} />
              <label className="removal-choice">
                <input
                  type="checkbox"
                  checked={closed}
                  onChange={(e) => {
                    setClosed(e.target.checked);
                    setConfirmed(false);
                  }}
                />
                Close this posting month
              </label>
              <label>
                Period change reason
                <textarea
                  value={reason}
                  maxLength={500}
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
              <p>
                Closing blocks journal creation, submission, approval and
                posting for this entity and month. Reopening preserves the
                earlier immutable revision. This does not complete a full close
                checklist.
              </p>
              <label className="removal-choice">
                <input
                  type="checkbox"
                  checked={confirmed}
                  onChange={(e) => setConfirmed(e.target.checked)}
                />
                I reviewed the entity, month and posting impact.
              </label>
              <button
                className="button primary"
                disabled={c.busy || !confirmed}
              >
                Save posting period control
              </button>
            </form>
          )}
        </>
      )}
    </section>
  );
}
function HistoryPanel({
  entity,
  revision,
}: {
  entity: string;
  revision: number;
}) {
  const [page, setPage] = useState(0);
  const result = useFinancialRead<Page<Audit>>(
    `/api/v1/finance/history?${new URLSearchParams({ entity, page: String(page) })}`,
    revision,
  );
  return (
    <section className="time-panel">
      <h3>Financial history</h3>
      {!result ? (
        <p role="status">Loading history…</p>
      ) : result.error ? (
        <FinancialError message={result.error} />
      ) : (
        <>
          {!result.data!.items.length && <p>No financial changes recorded.</p>}
          {result.data!.items.map((a) => (
            <article className="time-record" key={a.id}>
              <div>
                <h4>
                  {a.action} · {a.actor}
                </h4>
                <p>
                  {a.reason} · {a.recordedAt}
                </p>
                <p>
                  Document {a.documentId} · Correlation {a.correlationId}
                </p>
                <details>
                  <summary>Before and after</summary>
                  <pre>{a.before}</pre>
                  <pre>{a.after}</pre>
                </details>
              </div>
            </article>
          ))}
          <FinancialPager
            page={page}
            total={result.data!.total}
            onPage={setPage}
          />
        </>
      )}
    </section>
  );
}
