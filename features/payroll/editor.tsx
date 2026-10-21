"use client";
import { useState } from "react";
import { Modal } from "@/components/ui/primitives";
import type { Sheet } from "@/features/timekeeping/contracts";
import type { Page, Ledger } from "@/features/finance/contracts";
import {
  FinancialError,
  FinancialPager,
  useFinancialCommand,
  useFinancialDraft,
  useFinancialRead,
} from "@/features/finance/shared";
import type { Run } from "./contracts";
export function PayrollEditor({
  entity,
  ledger,
  run,
  onClose,
  onSaved,
}: {
  entity: string;
  ledger: Ledger | undefined;
  run: Run | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [authoritative, setAuthoritative] = useState(
    run?.ruleVersion.startsWith("DEMO-COMPENSATION") ?? true,
  );
  const c = useFinancialCommand(),
    [dirty, setDirty] = useState(false),
    [source, setSource] = useState(run?.source.id ?? ""),
    [date, setDate] = useState(
      () => run?.postingDate ?? new Date().toISOString().slice(0, 10),
    ),
    [rate, setRate] = useState(run ? String(run.hourlyRate) : ""),
    [deduction, setDeduction] = useState(run ? String(run.deduction) : "0"),
    [cost, setCost] = useState(run?.costCentre ?? ledger?.costCentres[0] ?? ""),
    [reason, setReason] = useState(""),
    [page, setPage] = useState(0);
  useFinancialDraft(dirty);
  const approved = useFinancialRead<Page<Sheet>>(
    `/api/v1/time/sheets?review=true&status=APPROVED&page=${page}`,
    0,
  );
  const close = () => {
    if (!c.busy && (!dirty || confirm("Discard unsaved payroll inputs?")))
      onClose();
  };
  return (
    <Modal
      title={
        run
          ? "Recalculate demonstration payroll"
          : "Calculate approved time payroll"
      }
      onClose={close}
      closeDisabled={c.busy}
      wide
    >
      <form
        className="change-form"
        noValidate
        onChange={() => setDirty(true)}
        onSubmit={async (e) => {
          e.preventDefault();
          const r = await c.run<Run>(
            authoritative
              ? "/api/v1/payroll/compensation-runs"
              : "/api/v1/payroll/runs",
            {
              id: run?.id ?? null,
              version: run?.version ?? 0,
              entity,
              sheetId: source,
              costCentre: cost,
              postingDate: date,
              hourlyRate: authoritative ? null : rate,
              deduction,
              reason,
            },
          );
          if (r) {
            setDirty(false);
            onSaved();
          }
        }}
      >
        <p>
          DEMONSTRATION ONLY · USD · approved minutes × entered hourly rate,
          rounded to two decimal places using half-even, less the entered
          voluntary deduction. No statutory tax, employer contribution, salary
          proration, overtime premium or benefit deduction is calculated. This
          calculation cannot release money.
        </p>
        <label>
          Calculation source
          <select
            value={authoritative ? "COMPENSATION" : "HOURLY"}
            disabled={!!run}
            onChange={(e) =>
              setAuthoritative(e.target.value === "COMPENSATION")
            }
          >
            <option value="COMPENSATION">
              Authoritative compensation and approved bonus inputs
            </option>
            <option value="HOURLY">Entered hourly demonstration rule</option>
          </select>
        </label>
        {authoritative && (
          <p>
            Each worked date uses its effective approved annual salary divided
            by its reviewed annual paid-hours basis (hourly rate: half-even to 4
            decimals). Period base pay rounds half-even to 2 decimals, plus
            unconsumed approved bonuses effective by this week end. This is an
            explicit demonstration conversion, not a statutory salaried-payroll
            proration policy. Salary and bonus inputs are frozen with this
            calculation; bonuses are reserved to exactly one run. A returned run
            retains that snapshot.
          </p>
        )}
        <FinancialError message={c.error} fields={c.fields} />
        {!run && (
          <details>
            <summary>
              Choose approved time in your authorised review scope
            </summary>
            {!approved ? (
              <p role="status">Loading approved time…</p>
            ) : approved.error ? (
              <FinancialError message={approved.error} />
            ) : (
              <>
                {!approved.data!.items.length && (
                  <p>
                    No approved time in this scope. Approve a worker timesheet
                    first.
                  </p>
                )}
                {approved.data!.items.map((s) => (
                  <p key={s.id}>
                    <button
                      type="button"
                      className="button secondary"
                      onClick={() => {
                        setSource(s.id);
                        setDirty(true);
                      }}
                    >
                      Use {s.employeeId} · {s.week} · {s.totalMinutes} minutes
                    </button>
                  </p>
                ))}
                <FinancialPager
                  page={page}
                  total={approved.data!.total}
                  onPage={setPage}
                />
              </>
            )}
          </details>
        )}
        <label>
          Approved timesheet ID *
          <input
            value={source}
            required
            readOnly={!!run}
            onChange={(e) => setSource(e.target.value)}
          />
        </label>
        <p>
          The source worker must be assigned to this existing Core HR legal
          entity. Current entity and reference dates are checked; historical
          transfers and pay-group eligibility remain unimplemented. Source time
          can belong to only one payroll run.
        </p>
        <div className="time-form-grid">
          <label>
            Payroll accounting date *
            <input
              type="date"
              required
              value={date}
              onChange={(e) => setDate(e.target.value)}
            />
          </label>
          {!authoritative && (
            <label>
              Hourly rate (USD) *
              <input
                inputMode="decimal"
                required
                value={rate}
                onChange={(e) => setRate(e.target.value)}
              />
            </label>
          )}
          <label>
            Voluntary deduction (USD) *
            <input
              inputMode="decimal"
              required
              value={deduction}
              onChange={(e) => setDeduction(e.target.value)}
            />
          </label>
          <label>
            Payroll cost centre *
            <select value={cost} onChange={(e) => setCost(e.target.value)}>
              {(ledger?.costCentres ?? []).map((x) => (
                <option key={x}>{x}</option>
              ))}
            </select>
          </label>
        </div>
        <label>
          Payroll calculation reason *
          <textarea
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            disabled={c.busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={c.busy || !ledger}>
            Calculate demonstration result
          </button>
        </div>
      </form>
    </Modal>
  );
}
