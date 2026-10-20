"use client";
import { useState } from "react";
import type { Actor } from "@/lib/contracts";
import { Badge } from "@/components/ui/primitives";
import { amount, type Context, type Page } from "@/features/finance/contracts";
import {
  FinancialError,
  FinancialPager,
  useFinancialRead,
} from "@/features/finance/shared";
import { PayrollEditor } from "./editor";
import { PayrollDetail } from "./detail";
import type { Run } from "./contracts";
export function PayrollWorkspace({ actor }: { actor: Actor }) {
  const [revision, refresh] = useState(0),
    [chosen, setChosen] = useState(""),
    [page, setPage] = useState(0),
    [selected, setSelected] = useState<string | null>(null),
    [edit, setEdit] = useState<Run | "new" | null>(null),
    [notice, setNotice] = useState("");
  const ctx = useFinancialRead<Context>("/api/v1/finance/context", revision),
    grants =
      ctx?.data?.grants.filter((g) => g.permission.startsWith("PAYROLL_")) ??
      [],
    entity = chosen || grants[0]?.entity || "",
    ledger = ctx?.data?.ledgers.find((l) => l.entity === entity),
    canPrepare = grants.some(
      (g) => g.entity === entity && g.permission === "PAYROLL_PREPARE",
    );
  const records = useFinancialRead<Page<Run>>(
    `/api/v1/payroll/runs?${new URLSearchParams({ entity, page: String(page) })}`,
    revision,
  );
  const saved = () => {
    setSelected(null);
    setEdit(null);
    setNotice("Payroll command committed. Results reloaded from the server.");
    refresh((n) => n + 1);
  };
  return (
    <section className="time-workspace" aria-label="Payroll workspace">
      <div className="time-intro">
        <span className="eyebrow">PAYROLL CONTROL</span>
        <h2>From approved time to explained pay.</h2>
        <p>
          Review inputs, resolve exceptions and separate finalization from
          payments and accounting.
        </p>
        <small>
          Demonstration hourly rule only. Countries, pay calendars, statutory
          rules, banks and production payroll policy remain unconfigured.
        </small>
      </div>
      {!ctx ? (
        <p role="status">Loading payroll access…</p>
      ) : ctx.error ? (
        <FinancialError message={ctx.error} />
      ) : (
        <section className="time-panel">
          <div className="time-section-header">
            <h3>
              {grants.length ? "Payroll register" : "My released payslips"}
            </h3>
            {canPrepare && (
              <button
                className="button primary"
                disabled={!ledger}
                onClick={() => setEdit("new")}
              >
                Calculate payroll
              </button>
            )}
          </div>
          {grants.length > 0 && (
            <label>
              Payroll legal entity
              <select
                value={entity}
                onChange={(e) => {
                  setChosen(e.target.value);
                  setPage(0);
                }}
              >
                {Array.from(new Set(grants.map((g) => g.entity))).map((e) => (
                  <option key={e}>{e}</option>
                ))}
              </select>
            </label>
          )}
          {canPrepare && !ledger && (
            <p>
              Configure the demonstration ledger in Finance & operations after
              declaring the legal entity in Settings.
            </p>
          )}
          <button
            className="button secondary"
            onClick={() => refresh((n) => n + 1)}
          >
            Refresh payroll
          </button>
          {notice && <p role="status">{notice}</p>}
          {!records ? (
            <p role="status">Loading payroll records…</p>
          ) : records.error ? (
            <FinancialError message={records.error} />
          ) : (
            <>
              {!records.data!.items.length && (
                <p>
                  {grants.length
                    ? "No payroll results in this entity. Approved time, current entity assignment and ledger configuration are prerequisites."
                    : "No released payslips are available for your identity."}
                </p>
              )}
              <div className="time-records">
                {records.data!.items.map((r) => (
                  <article className="time-record" key={r.id}>
                    <div>
                      <h4>
                        {r.employeeId} · {r.source.week}
                      </h4>
                      <Badge status={r.status} />
                      <p>
                        {r.currency} gross {amount(r.gross)} · deduction{" "}
                        {amount(r.deduction)} · net {amount(r.net)}
                      </p>
                      <p>
                        Payment {r.paymentStatus} · Accounting{" "}
                        {r.journalId ? "Journal prepared" : "Not prepared"}
                      </p>
                      <p>{r.exceptions.length} blocking exceptions</p>
                    </div>
                    <button
                      className="button secondary"
                      aria-label={`View payroll result ${r.id}`}
                      onClick={() => setSelected(r.id)}
                    >
                      View payroll result
                    </button>
                  </article>
                ))}
              </div>
              <FinancialPager
                page={page}
                total={records.data!.total}
                onPage={setPage}
              />
            </>
          )}
        </section>
      )}
      {selected && (
        <PayrollDetail
          id={selected}
          actor={actor}
          grants={grants}
          onClose={() => setSelected(null)}
          onSaved={saved}
          onEdit={(r) => {
            setSelected(null);
            setEdit(r);
          }}
        />
      )}
      {edit && (
        <PayrollEditor
          entity={entity}
          ledger={ledger}
          run={edit === "new" ? null : edit}
          onClose={() => setEdit(null)}
          onSaved={saved}
        />
      )}
    </section>
  );
}
