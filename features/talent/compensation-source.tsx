"use client";
import { useState } from "react";
import type { Context } from "./contracts";
import { TalentForm, Field, Currency, value, useTalentRead } from "./shared";
import { amount } from "@/features/finance/contracts";
export type Salary = {
  id: string;
  employeeId: string;
  currency: string;
  annualSalary: string;
  annualHours: string;
  effectiveDate: string;
  episodeStart: string;
  sourceId: string;
  revision: number;
};
type Setup = {
  id: string;
  input: {
    annualSalary: string;
    annualHours: string;
    currency: string;
    effectiveDate: string;
    reason: string;
  };
  preparedBy: string;
  status: string;
  reviewedBy: string | null;
  reviewReason: string | null;
};
type State = { setups: Setup[]; salaries: Salary[] };
export function CompensationSource({ context }: { context: Context }) {
  const [employee, setEmployee] = useState(context.workers.items[0]?.id ?? ""),
    [revision, refresh] = useState(0);
  return (
    <section className="time-panel">
      <h3>Authoritative payroll compensation</h3>
      <p>
        Payroll owns effective annual salary and the explicitly approved annual
        paid-hours basis. Two different HR employees establish the initial
        baseline. Subsequent changes come from approved talent plans. USD
        demonstration calculation only; statutory rules are not configured.
      </p>
      <label>
        Compensation worker
        <select value={employee} onChange={(e) => setEmployee(e.target.value)}>
          {context.workers.items.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name} · {w.id}
            </option>
          ))}
        </select>
      </label>
      {employee && (
        <SourceDetail
          key={employee}
          employee={employee}
          revision={revision}
          saved={() => refresh((n) => n + 1)}
        />
      )}
    </section>
  );
}
function SourceDetail({
  employee,
  revision,
  saved,
}: {
  employee: string;
  revision: number;
  saved: () => void;
}) {
  const state = useTalentRead<State>(
    `/api/v1/payroll/compensation/employees/${encodeURIComponent(employee)}`,
    revision,
  );
  return !state ? (
    <p role="status">Loading compensation source…</p>
  ) : state.error ? (
    <p role="alert">{state.error}</p>
  ) : (
    <>
      {!state.data?.salaries.length && (
        <TalentForm
          url="/api/v1/payroll/compensation/baselines"
          label="Submit payroll compensation baseline"
          onSaved={saved}
          build={(f) => ({
            employeeId: employee,
            currency: value(f, "currency"),
            annualSalary: value(f, "annualSalary"),
            annualHours: value(f, "annualHours"),
            effectiveDate: value(f, "effectiveDate"),
            reason: value(f, "reason"),
          })}
        >
          <Currency />
          <Field name="annualSalary" label="Verified annual salary" />
          <Field name="annualHours" label="Approved annual paid-hours basis" />
          <Field
            name="effectiveDate"
            label="Baseline effective date"
            type="date"
          />
          <p>
            Enter the approved business basis; no legal or statutory
            working-hours default is assumed.
          </p>
        </TalentForm>
      )}
      {state.data?.setups.map((s) => (
        <article key={s.id} className="talent-subcard">
          <h4>Baseline {s.status}</h4>
          <p>
            {s.input.currency} {amount(s.input.annualSalary)} per year /{" "}
            {s.input.annualHours} paid hours · from {s.input.effectiveDate}
          </p>
          <p>
            Prepared by {s.preparedBy}: {s.input.reason}
          </p>
          {s.status === "SUBMITTED" ? (
            <TalentForm
              url={`/api/v1/payroll/compensation/baselines/${s.id}/decisions`}
              label="Review compensation baseline"
              onSaved={saved}
              confirm="Record an independent baseline review? Approval establishes the payroll-owned annual salary and paid-hours basis for this worker."
              build={(f) => ({
                action: value(f, "action"),
                reason: value(f, "reason"),
              })}
            >
              <label>
                Baseline decision
                <select name="action">
                  <option value="APPROVE">Approve</option>
                  <option value="REJECT">Reject</option>
                </select>
              </label>
            </TalentForm>
          ) : (
            <p>
              Reviewed by {s.reviewedBy}: {s.reviewReason}
            </p>
          )}
        </article>
      ))}
      <h4>Effective salary history (latest 200 revisions)</h4>
      {!state.data?.salaries.length && (
        <p>
          No approved salary baseline. Compensation plans and authoritative
          payroll calculation are blocked until independent approval.
        </p>
      )}
      {state.data?.salaries.map((s) => (
        <p key={s.id}>
          Revision {s.revision} · {s.effectiveDate} · {s.currency}{" "}
          {amount(s.annualSalary)} / {s.annualHours} annual hours · source{" "}
          {s.sourceId}
        </p>
      ))}
    </>
  );
}
export function ProposalSalary({
  employee,
  date,
}: {
  employee: string;
  date: string;
}) {
  const salary = useTalentRead<Salary>(
    `/api/v1/payroll/compensation/employees/${encodeURIComponent(employee)}/salary?date=${encodeURIComponent(date)}`,
    0,
  );
  return !salary ? (
    <p role="status">Loading authoritative salary…</p>
  ) : salary.error ? (
    <p role="alert">{salary.error}</p>
  ) : (
    <p>
      Authoritative current salary: {salary.data?.currency}{" "}
      {amount(salary.data?.annualSalary ?? "0")} · revision{" "}
      {salary.data?.revision}. Revalidated by the server at save and approval.
    </p>
  );
}
export function ProposalRow({
  context,
  index,
}: {
  context: Context;
  index: number;
}) {
  const [employee, setEmployee] = useState(""),
    [date, setDate] = useState("");
  return (
    <fieldset>
      <legend>Proposal {index + 1}</legend>
      <label>
        Worker *
        <select
          name={`employeeId${index}`}
          required
          value={employee}
          onChange={(e) => setEmployee(e.target.value)}
        >
          <option value="" disabled>
            Choose a worker
          </option>
          {context.workers.items.map((w) => (
            <option key={w.id} value={w.id}>
              {w.name} · {w.id}
            </option>
          ))}
        </select>
      </label>
      <label>
        Effective date *
        <input
          name={`effectiveDate${index}`}
          type="date"
          required
          value={date}
          onChange={(e) => setDate(e.target.value)}
        />
      </label>
      {employee && date && <ProposalSalary employee={employee} date={date} />}
      <Field name={`proposedSalary${index}`} label="Proposed annual salary" />
      <Field name={`bonus${index}`} label="One-time bonus" value="0.00" />
    </fieldset>
  );
}
