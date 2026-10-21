"use client";
import { useFinancialRead, FinancialError } from "@/features/finance/shared";
import { amount } from "@/features/finance/contracts";
import type { Salary } from "@/features/talent/compensation-source";
type Calculation = {
  baseGross: string;
  bonus: string;
  gross: string;
  ruleVersion: string;
  days: { date: string; minutes: number; salary: Salary; hourlyRate: string }[];
  bonuses: {
    id: string;
    sourceId: string;
    amount: string;
    effectiveDate: string;
  }[];
};
export function CompensationSnapshot({ id }: { id: string }) {
  const r = useFinancialRead<Calculation>(
    `/api/v1/payroll/compensation/calculations/${encodeURIComponent(id)}`,
    0,
  );
  return (
    <section aria-label="Immutable compensation calculation">
      <h4>Authoritative compensation snapshot</h4>
      {!r ? (
        <p role="status">Loading calculation explanation…</p>
      ) : r.error ? (
        <FinancialError message={r.error} />
      ) : (
        r.data && (
          <>
            <p>
              Base pay {amount(r.data.baseGross)} + approved bonuses{" "}
              {amount(r.data.bonus)} = gross {amount(r.data.gross)}.
            </p>
            {r.data.days.map((d) => (
              <p key={d.date}>
                {d.date}: {d.minutes} minutes at {d.hourlyRate}/hour · salary
                revision {d.salary.revision} · annual salary{" "}
                {amount(d.salary.annualSalary)} / {d.salary.annualHours} hours ·
                source {d.salary.sourceId}
              </p>
            ))}
            {r.data.bonuses.map((b) => (
              <p key={b.id}>
                Bonus {amount(b.amount)} · effective {b.effectiveDate} · plan{" "}
                {b.sourceId} · reserved to this run
              </p>
            ))}
            <p>
              Rule {r.data.ruleVersion}. No statutory calculations or payment
              settlement.
            </p>
          </>
        )
      )}
    </section>
  );
}
