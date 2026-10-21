"use client";
import { useState } from "react";
import { TalentForm, Field, useTalentRead, value } from "./shared";
type Binding = {
  request: {
    id: string;
    federatedSubject: string;
    preparedBy: string;
    reason: string;
    effectiveFrom: string;
    recordedAt: string;
  };
  decision: {
    decision: string;
    actor: string;
    reason: string;
    recordedAt: string;
  } | null;
};
type Context = {
  issuer: string;
  employeeVersion: number;
  employmentStatus: string;
  startDate: string;
  bindings: Binding[];
};
export function InitialIdentity({ employeeId }: { employeeId: string }) {
  const [revision, refresh] = useState(0);
  const c = useTalentRead<Context>(
    `/api/v1/initial-identities/employees/${encodeURIComponent(employeeId)}`,
    revision,
  );
  const saved = () => refresh((n) => n + 1);
  return (
    <section
      className="talent-subcard"
      aria-label="Reviewed new-hire SSO binding"
    >
      <h4>New-hire SSO binding</h4>
      <p>
        Verify the subject directly in the configured identity provider. Two
        different HR employees must prepare and review the binding. This grants
        Employee access from the employment start date; it does not create an
        identity-provider account.
      </p>
      {!c ? (
        <p role="status">Loading identity review…</p>
      ) : c.error ? (
        <p role="alert">{c.error}</p>
      ) : (
        c.data && (
          <>
            <p>
              Provider: {c.data.issuer || "Not configured"} · employment{" "}
              {c.data.employmentStatus} · access from {c.data.startDate}
            </p>
            {!c.data.bindings.some(
              (b) => b.decision?.decision === "APPROVE",
            ) && (
              <TalentForm
                url={`/api/v1/initial-identities/employees/${encodeURIComponent(employeeId)}`}
                label="Submit verified identity binding"
                onSaved={saved}
                disabled={!c.data.issuer}
                build={(f) => ({
                  employeeVersion: c.data!.employeeVersion,
                  federatedSubject: value(f, "federatedSubject"),
                  reason: value(f, "reason"),
                })}
              >
                <Field
                  name="federatedSubject"
                  label="Verified provider subject (exact sub claim)"
                  max={120}
                />
              </TalentForm>
            )}
            {!c.data.bindings.length && <p>No identity bindings submitted.</p>}
            {c.data.bindings.map((b) => (
              <article key={b.request.id}>
                <p>
                  {b.request.federatedSubject} ·{" "}
                  {b.decision?.decision ?? "Awaiting independent review"}
                </p>
                <p>
                  Prepared by {b.request.preparedBy}: {b.request.reason}
                </p>
                {b.decision ? (
                  <p>
                    Reviewed by {b.decision.actor}: {b.decision.reason}
                  </p>
                ) : (
                  <TalentForm
                    url={`/api/v1/initial-identities/${b.request.id}/review`}
                    label="Record identity review"
                    onSaved={saved}
                    confirm={`Review binding ${b.request.federatedSubject} to employee ${employeeId}? Approval grants Employee access from ${b.request.effectiveFrom}.`}
                    build={(f) => ({
                      decision: value(f, "decision"),
                      reason: value(f, "reason"),
                    })}
                  >
                    <label>
                      Identity decision *
                      <select name="decision">
                        <option value="APPROVE">
                          Approve verified binding
                        </option>
                        <option value="REJECT">Reject binding</option>
                      </select>
                    </label>
                  </TalentForm>
                )}
              </article>
            ))}
          </>
        )
      )}
    </section>
  );
}
