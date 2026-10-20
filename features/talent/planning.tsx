"use client";
import { useState } from "react";
import type {
  Context,
  Compensation,
  Succession,
  Scenario,
  Intake,
} from "./contracts";
import { amount } from "@/features/finance/contracts";
import {
  TalentForm,
  Field,
  Notes,
  WorkerField,
  Currency,
  value,
  Decision,
  useTalentRead,
  type Saved,
} from "./shared";
export function CompensationEditor({
  context,
  onSaved,
}: {
  context: Context;
  onSaved: Saved;
}) {
  const [rows, setRows] = useState(1);
  return (
    <TalentForm
      url="/api/v1/talent/compensation"
      onSaved={onSaved}
      label="Save compensation plan"
      build={(f) => ({
        title: value(f, "title"),
        currency: value(f, "currency"),
        budget: value(f, "budget"),
        proposals: Array.from({ length: rows }, (_, i) => ({
          employeeId: value(f, `employeeId${i}`),
          currentSalary: value(f, `currentSalary${i}`),
          proposedSalary: value(f, `proposedSalary${i}`),
          bonus: value(f, `bonus${i}`),
          effectiveDate: value(f, `effectiveDate${i}`),
        })),
        reason: value(f, "reason"),
      })}
    >
      <Field name="title" label="Compensation plan name" />
      <Currency />
      <Field name="budget" label="Planning budget" />
      <p>
        Budget usage is annual salary increase plus one-time bonus. Current
        salaries are entered snapshots; validate them against your approved
        compensation source. No exchange-rate conversion.
      </p>
      {Array.from({ length: rows }, (_, i) => (
        <fieldset key={i}>
          <legend>Proposal {i + 1}</legend>
          <WorkerField
            workers={context.workers.items}
            name={`employeeId${i}`}
          />
          <Field name={`currentSalary${i}`} label="Current annual salary" />
          <Field name={`proposedSalary${i}`} label="Proposed annual salary" />
          <Field name={`bonus${i}`} label="One-time bonus" value="0.00" />
          <Field
            name={`effectiveDate${i}`}
            label="Effective date"
            type="date"
          />
        </fieldset>
      ))}
      <button
        type="button"
        className="button secondary"
        disabled={rows >= 50}
        onClick={() => setRows((n) => n + 1)}
      >
        Add proposal
      </button>
    </TalentForm>
  );
}
export function CompensationDetail({
  r,
  onSaved,
}: {
  r: Compensation;
  onSaved: Saved;
}) {
  return (
    <>
      <p>
        {r.currency} · budget {amount(r.budget)} · proposed cost{" "}
        {amount(r.total)}
      </p>
      {r.proposals.map((p) => (
        <article className="talent-subcard" key={p.employeeId}>
          <h4>
            {p.employeeId} · effective {p.effectiveDate}
          </h4>
          <p>
            Annual salary {amount(p.currentSalary)} → {amount(p.proposedSalary)}{" "}
            · bonus {amount(p.bonus)}
          </p>
        </article>
      ))}
      <Decision
        kind="compensation"
        record={r}
        onSaved={onSaved}
        actions={
          r.meta.status === "DRAFT" || r.meta.status === "RETURNED"
            ? ["SUBMIT"]
            : r.meta.status === "SUBMITTED"
              ? ["APPROVE", "RETURN"]
              : []
        }
      />
      {r.payrollIntakeId && (
        <p role="status">
          Payroll intake {r.payrollIntakeId}: RECEIVED. This is a durable
          handoff awaiting payroll consumption, not calculated or paid
          compensation.
        </p>
      )}
    </>
  );
}
export function SuccessionEditor({
  context,
  onSaved,
}: {
  context: Context;
  onSaved: Saved;
}) {
  const [rows, setRows] = useState(1);
  return (
    <TalentForm
      url="/api/v1/talent/succession"
      onSaved={onSaved}
      label="Save succession plan"
      build={(f) => ({
        position: value(f, "position"),
        nominees: Array.from({ length: rows }, (_, i) => ({
          employeeId: value(f, `employeeId${i}`),
          skills: value(f, `skills${i}`),
          readiness: value(f, `readiness${i}`),
          assessment: value(f, `assessment${i}`),
        })),
        reason: value(f, "reason"),
      })}
    >
      <Field name="position" label="Critical position or role" />
      {Array.from({ length: rows }, (_, i) => (
        <fieldset key={i}>
          <legend>Successor {i + 1}</legend>
          <WorkerField
            workers={context.workers.items}
            name={`employeeId${i}`}
          />
          <Notes
            name={`skills${i}`}
            label="Talent profile and demonstrated skills"
          />
          <label>
            Readiness *
            <select name={`readiness${i}`}>
              <option value="READY_NOW">Ready now</option>
              <option value="WITHIN_ONE_YEAR">Within one year</option>
              <option value="ONE_TO_THREE_YEARS">One to three years</option>
            </select>
          </label>
          <Notes name={`assessment${i}`} label="Readiness assessment" />
        </fieldset>
      ))}
      <button
        type="button"
        className="button secondary"
        disabled={rows >= 30}
        onClick={() => setRows((n) => n + 1)}
      >
        Add nominee
      </button>
      <p>
        Confidential HR-only planning. A nomination does not promote, appoint or
        change the worker’s access.
      </p>
    </TalentForm>
  );
}
export function SuccessionDetail({
  r,
  context,
  onSaved,
}: {
  r: Succession;
  context: Context;
  onSaved: Saved;
}) {
  return (
    <>
      {r.nominees.map((n) => (
        <article className="talent-subcard" key={n.employeeId}>
          <h4>
            {n.employeeId} · {n.readiness.replaceAll("_", " ")}
          </h4>
          <p>Talent profile: {n.skills}</p>
          <p>Assessment: {n.assessment}</p>
        </article>
      ))}
      <details>
        <summary>Update a nominee or add a successor</summary>
        <TalentForm
          url={`/api/v1/talent/succession/${r.meta.id}/assessments`}
          onSaved={onSaved}
          label="Record readiness assessment"
          build={(f) => ({
            version: r.meta.version,
            employeeId: value(f, "employeeId"),
            skills: value(f, "skills"),
            readiness: value(f, "readiness"),
            assessment: value(f, "assessment"),
            reason: value(f, "reason"),
          })}
        >
          <WorkerField workers={context.workers.items} />
          <Notes name="skills" label="Current talent profile" />
          <label>
            Readiness *
            <select name="readiness">
              <option value="READY_NOW">Ready now</option>
              <option value="WITHIN_ONE_YEAR">Within one year</option>
              <option value="ONE_TO_THREE_YEARS">One to three years</option>
            </select>
          </label>
          <Notes name="assessment" label="Updated readiness assessment" />
          <p>
            Changes retain the prior profile and readiness in the audit history.
          </p>
        </TalentForm>
      </details>
    </>
  );
}
export function ScenarioEditor({
  context,
  onSaved,
}: {
  context: Context;
  onSaved: Saved;
}) {
  const [rows, setRows] = useState(1);
  return (
    <TalentForm
      url="/api/v1/talent/scenarios"
      onSaved={onSaved}
      label="Calculate and save scenario"
      build={(f) => ({
        title: value(f, "title"),
        currency: value(f, "currency"),
        asOf: new Date().toISOString().slice(0, 10),
        workerIds: f.getAll("workerIds").map(String),
        lines: Array.from({ length: rows }, (_, i) => ({
          dimension: value(f, `dimension${i}`),
          baselineHeadcount: Number(value(f, `baselineHeadcount${i}`)),
          proposedHeadcount: Number(value(f, `proposedHeadcount${i}`)),
          annualUnitCost: value(f, `annualUnitCost${i}`),
        })),
        reason: value(f, "reason"),
      })}
    >
      <Field name="title" label="Scenario name" />
      <Currency />
      <fieldset>
        <legend>Current workers in baseline (selected workers only)</legend>
        {context.workers.items.map((w) => (
          <label key={w.id}>
            <input name="workerIds" type="checkbox" value={w.id} />
            {w.name} · {w.id} · {w.department}
          </label>
        ))}
      </fieldset>
      <p>
        The baseline headcount must equal the selected current-worker count.
        Enter approved annual unit-cost assumptions; this is a planning model,
        not payroll forecasting or a financial posting.
      </p>
      {Array.from({ length: rows }, (_, i) => (
        <fieldset key={i}>
          <legend>Dimension {i + 1}</legend>
          <Field name={`dimension${i}`} label="Cost dimension" />
          <Field
            name={`baselineHeadcount${i}`}
            label="Baseline headcount"
            type="number"
          />
          <Field
            name={`proposedHeadcount${i}`}
            label="Proposed headcount"
            type="number"
          />
          <Field name={`annualUnitCost${i}`} label="Annual unit cost" />
        </fieldset>
      ))}
      <button
        type="button"
        className="button secondary"
        disabled={rows >= 50}
        onClick={() => setRows((n) => n + 1)}
      >
        Add dimension
      </button>
    </TalentForm>
  );
}
export function ScenarioDetail({ r }: { r: Scenario }) {
  return (
    <>
      <p>
        Snapshot {r.asOf} · {r.currency} · {r.workerSnapshot.length} current
        workers
      </p>
      <dl>
        <dt>Baseline headcount</dt>
        <dd>{r.baselineHeadcount}</dd>
        <dt>Proposed headcount</dt>
        <dd>{r.proposedHeadcount}</dd>
        <dt>Baseline annual cost</dt>
        <dd>{amount(r.baselineCost)}</dd>
        <dt>Proposed annual cost</dt>
        <dd>{amount(r.proposedCost)}</dd>
        <dt>Annual cost variance</dt>
        <dd>{amount(r.variance)}</dd>
      </dl>
      {r.lines.map((l) => (
        <p key={l.dimension}>
          {l.dimension}: {l.baselineHeadcount} → {l.proposedHeadcount} workers
          at {amount(l.annualUnitCost)} per year
        </p>
      ))}
    </>
  );
}
export function PayrollIntakes({ revision }: { revision: number }) {
  const [page, setPage] = useState(0);
  const list = useTalentRead<Intake[]>(
    `/api/v1/payroll/talent-inputs?page=${page}`,
    revision,
  );
  return (
    <section className="time-panel">
      <h3>Payroll compensation intake</h3>
      <p>
        Payroll-owned receipts awaiting calculation support. RECEIVED does not
        mean applied, paid or posted.
      </p>
      {!list ? (
        <p role="status">Loading intake…</p>
      ) : list.error ? (
        <p role="alert">{list.error}</p>
      ) : (
        <>
          {!list.data?.length && <p>No approved compensation receipts.</p>}
          {list.data?.map((i) => (
            <p key={i.id}>
              {i.sourceId} · {i.currency} · {i.awards.length} awards ·{" "}
              {i.status}
            </p>
          ))}
          <div className="time-pager">
            <button disabled={page === 0} onClick={() => setPage((n) => n - 1)}>
              Previous intake page
            </button>
            <span>Page {page + 1}</span>
            <button
              disabled={(list.data?.length ?? 0) < 20}
              onClick={() => setPage((n) => n + 1)}
            >
              Next intake page
            </button>
          </div>
        </>
      )}
    </section>
  );
}
