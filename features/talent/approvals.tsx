"use client";
import { useState } from "react";
import type { Actor } from "@/lib/contracts";
import type { Kind } from "./contracts";
import { TalentForm, Field, value, useTalentRead } from "./shared";
type Delegation = {
  id: string;
  kind: Kind;
  recordId: string;
  recordVersion: number;
  originalEmployee: string;
  delegateEmployee: string;
  from: string;
  through: string;
  actor: string;
  reason: string;
  recordedAt: string;
};
type Task = {
  kind: Kind;
  id: string;
  title: string;
  version: number;
  delegation: Delegation | null;
};
type History = {
  delegation: Delegation;
  revocation: { actor: string; reason: string; recordedAt: string } | null;
};
export function TalentApprovals({
  actor,
  revision,
  onOpen,
}: {
  actor: Actor;
  revision: number;
  onOpen: (kind: Kind, id: string) => void;
}) {
  const [page, setPage] = useState(0),
    [local, refresh] = useState(0);
  const tasks = useTalentRead<Task[]>(
    `/api/v1/talent/approvals?page=${page}`,
    revision + local,
  );
  const reviewers = useTalentRead<string[]>(
    "/api/v1/talent/approvals/delegates",
    revision + local,
  );
  return (
    <details className="time-panel">
      <summary>Talent approval inbox</summary>
      <p>
        Current independent requisition, compensation and review decisions.
        Delegation assigns responsibility to an already-authorized HR reviewer.
        It grants no additional record access or decision permissions.
      </p>
      {!tasks ? (
        <p role="status">Loading approval scope…</p>
      ) : tasks.error ? (
        <p role="alert">{tasks.error}</p>
      ) : (
        <>
          {!tasks.data?.length && (
            <p>
              No eligible submissions on this page. Continue to later pages for
              older submissions.
            </p>
          )}
          {tasks.data?.map((t) => (
            <article className="talent-subcard" key={t.kind + t.id}>
              <h4>{t.title}</h4>
              <p>
                {t.kind} · revision {t.version}
              </p>
              <button
                className="button secondary"
                onClick={() => onOpen(t.kind, t.id)}
              >
                Review {t.title}
              </button>
              {t.delegation ? (
                <>
                  <p>
                    Responsibility assigned by {t.delegation.originalEmployee}{" "}
                    to {t.delegation.delegateEmployee} from {t.delegation.from}{" "}
                    through {t.delegation.through}
                    {t.delegation.delegateEmployee === actor.employeeId
                      ? " · Assigned to you"
                      : ""}
                    .
                  </p>
                  <TalentForm
                    url={`/api/v1/talent/approvals/delegations/${t.delegation.id}/revoke`}
                    label="Revoke approval responsibility"
                    onSaved={() => refresh((n) => n + 1)}
                    confirm="Revoke this responsibility assignment? Existing reviewer permissions are unchanged."
                    build={(f) => ({ reason: value(f, "reason") })}
                  >
                    <p>{t.delegation.reason}</p>
                  </TalentForm>
                </>
              ) : (
                <details>
                  <summary>Delegate this approval responsibility</summary>
                  {!reviewers ? (
                    <p>Loading eligible reviewers…</p>
                  ) : reviewers.error ? (
                    <p role="alert">{reviewers.error}</p>
                  ) : (
                    <TalentForm
                      url="/api/v1/talent/approvals/delegations"
                      label="Assign approval responsibility"
                      onSaved={() => refresh((n) => n + 1)}
                      build={(f) => ({
                        kind: t.kind,
                        recordId: t.id,
                        version: t.version,
                        delegateEmployee: value(f, "delegateEmployee"),
                        from: value(f, "from"),
                        through: value(f, "through"),
                        reason: value(f, "reason"),
                      })}
                    >
                      <label>
                        Authorized HR reviewer *
                        <select
                          name="delegateEmployee"
                          required
                          defaultValue=""
                        >
                          <option value="" disabled>
                            Choose a different authorized reviewer
                          </option>
                          {reviewers.data?.map((id) => (
                            <option value={id} key={id}>
                              {id}
                            </option>
                          ))}
                        </select>
                      </label>
                      <Field
                        name="from"
                        label="Responsibility from"
                        type="date"
                        value={new Date().toISOString().slice(0, 10)}
                      />
                      <Field
                        name="through"
                        label="Responsibility through"
                        type="date"
                        value={new Date().toISOString().slice(0, 10)}
                      />
                      <p>
                        Assignment applies only to this submitted revision. A
                        return and resubmission requires a new assignment. All
                        current independence and scope checks remain in force.
                      </p>
                    </TalentForm>
                  )}
                </details>
              )}
            </article>
          ))}
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous approval page
          </button>
          <span>Page {page + 1}</span>
          <button
            disabled={page >= 10000}
            onClick={() => setPage((p) => p + 1)}
          >
            Next approval page
          </button>
        </>
      )}
    </details>
  );
}
export function ResponsibilityHistory({
  kind,
  id,
  revision,
}: {
  kind: Kind;
  id: string;
  revision: number;
}) {
  const [page, setPage] = useState(0);
  const h = useTalentRead<History[]>(
    `/api/v1/talent/approvals/history?${new URLSearchParams({ kind, id, page: String(page) })}`,
    revision,
  );
  return (
    <details>
      <summary>Approval responsibility history</summary>
      {!h ? (
        <p role="status">Loading responsibility history…</p>
      ) : h.error ? (
        <p role="alert">{h.error}</p>
      ) : (
        <>
          {!h.data?.length && <p>No responsibility assignments recorded.</p>}
          {h.data?.map(({ delegation: d, revocation: r }) => (
            <article key={d.id}>
              <p>
                Revision {d.recordVersion} · {d.originalEmployee} →{" "}
                {d.delegateEmployee} · {d.from}–{d.through}
              </p>
              <p>
                {d.actor} at {new Date(d.recordedAt).toLocaleString()}:{" "}
                {d.reason}
              </p>
              {r && (
                <p>
                  Revoked by {r.actor}: {r.reason}
                </p>
              )}
            </article>
          ))}
          <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            Previous responsibility history
          </button>
          <button
            disabled={(h.data?.length ?? 0) < 20}
            onClick={() => setPage((p) => p + 1)}
          >
            Next responsibility history
          </button>
        </>
      )}
    </details>
  );
}
