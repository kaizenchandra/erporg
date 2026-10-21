"use client";
import { useState } from "react";
import { TalentForm, Field, value, useTalentRead } from "./shared";
type Policy = {
  id: string;
  version: number;
  enabled: boolean;
  thresholdMinutes: number;
  frequencyMinutes: number;
  owner: string;
  reason: string;
};
type Run = {
  id: string;
  status: string;
  actor: string;
  scheduled: boolean;
  processed: number;
  delivered: number;
  skipped: number;
  reason: string;
  recordedAt: string;
};
type State = {
  policyId: string;
  version: number;
  status: string;
  failures: number;
  nextDue: string;
};
type Context = {
  policy: Policy | null;
  state: State | null;
  runs: Run[];
  schedulerEnabled: boolean;
};
type Item = {
  id: string;
  kind: string;
  recordId: string;
  status: string;
  reason: string;
  recipients: number;
};
export function TalentReminders({ revision }: { revision: number }) {
  const [local, refresh] = useState(0),
    [page, setPage] = useState(0);
  const c = useTalentRead<Context>(
    `/api/v1/talent/reminders?page=${page}`,
    revision + local,
  );
  const saved = () => refresh((n) => n + 1);
  return (
    <details className="time-panel">
      <summary>Approval reminder administration</summary>
      <p>
        Configure the age of submitted talent approvals before reminders are
        due. Business deadlines are not inferred. Delivery is to the in-app
        inbox, with at most one reminder per source event and reviewer per UTC
        day. Each bounded batch handles up to 20 submissions.
      </p>
      {!c ? (
        <p role="status">Loading reminder processing…</p>
      ) : c.error ? (
        <p role="alert">{c.error}</p>
      ) : (
        c.data && (
          <>
            <p>
              Background scheduler:{" "}
              {c.data.schedulerEnabled
                ? "Enabled"
                : "Disabled by deployment configuration; manual delivery is available"}
              .
            </p>
            <p>
              Processing: {c.data.state?.status ?? "Not configured"} · failures{" "}
              {c.data.state?.failures ?? 0} · next scheduled scan{" "}
              {c.data.state
                ? new Date(c.data.state.nextDue).toLocaleString()
                : "Not scheduled"}
              .
            </p>
            <TalentForm
              key={c.data.policy?.id ?? "new"}
              url="/api/v1/talent/reminders/policy"
              label="Save approval reminder policy"
              onSaved={saved}
              build={(f) => ({
                version: c.data?.policy?.version ?? 0,
                enabled: f.has("enabled"),
                thresholdMinutes: Number(value(f, "thresholdMinutes")),
                frequencyMinutes: Number(value(f, "frequencyMinutes")),
                reason: value(f, "reason"),
              })}
            >
              <label>
                <input
                  name="enabled"
                  type="checkbox"
                  defaultChecked={c.data.policy?.enabled ?? false}
                />
                Enable this tenant’s approval reminder policy
              </label>
              <Field
                name="thresholdMinutes"
                label="Submitted approval age before reminder (minutes)"
                type="number"
                value={
                  c.data.policy
                    ? String(c.data.policy.thresholdMinutes)
                    : undefined
                }
              />
              <Field
                name="frequencyMinutes"
                label="Scan frequency (minutes)"
                type="number"
                value={
                  c.data.policy
                    ? String(c.data.policy.frequencyMinutes)
                    : undefined
                }
              />
              <p>
                The policy owner’s current HR role and tenant are rechecked
                before delivery. Saving a revision records the current HR user
                as owner. No email or external provider delivery is configured.
              </p>
            </TalentForm>
            {c.data.policy?.enabled && (
              <>
                <TalentForm
                  url="/api/v1/talent/reminders/run"
                  label="Deliver due approval reminders"
                  onSaved={saved}
                  build={(f) => ({ reason: value(f, "reason") })}
                >
                  <p>
                    Recheck and deliver one batch now. Delivered/skipped item
                    receipts prevent duplicate notices when retried.
                  </p>
                </TalentForm>
                <TalentForm
                  url="/api/v1/talent/reminders/retry"
                  label="Authorize reminder processing retry"
                  onSaved={saved}
                  confirm="Reset the reminder failure stop after inspecting policy ownership and delivery errors? Existing notices and item receipts are preserved."
                  build={(f) => ({ reason: value(f, "reason") })}
                >
                  <p>
                    Processing stops after three failed background attempts.
                    Reconfigure a revoked owner or repair the fault, then
                    explicitly authorize a retry.
                  </p>
                </TalentForm>
              </>
            )}
            <button className="button secondary" onClick={saved}>
              Refresh reminder outcomes
            </button>
            {!c.data.runs.length && <p>No reminder processing attempts.</p>}
            {c.data.runs.map((r) => (
              <article
                className="talent-subcard"
                key={r.id}
                aria-label={`Reminder batch ${r.id}`}
              >
                <h4>Reminder batch {r.status}</h4>
                <p>
                  {r.scheduled ? "Background scheduler" : "Authorized command"}{" "}
                  · {r.actor} · {new Date(r.recordedAt).toLocaleString()}
                </p>
                <p>
                  {r.processed} submissions · {r.delivered} in-app notices ·{" "}
                  {r.skipped} skipped
                </p>
                <p>{r.reason}</p>
                <ReminderItems runId={r.id} />
              </article>
            ))}
            <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              Previous reminder batches
            </button>
            <span>Page {page + 1}</span>
            <button
              disabled={c.data.runs.length < 20}
              onClick={() => setPage((p) => p + 1)}
            >
              Next reminder batches
            </button>
          </>
        )
      )}
    </details>
  );
}
function ReminderItems({ runId }: { runId: string }) {
  const data = useTalentRead<Item[]>(
    `/api/v1/talent/reminders/runs/${encodeURIComponent(runId)}/items`,
    0,
  );
  return (
    <details>
      <summary>Inspect reminder item results</summary>
      {!data ? (
        <p role="status">Loading item outcomes…</p>
      ) : data.error ? (
        <p role="alert">{data.error}</p>
      ) : (
        <>
          {!data.data?.length && (
            <p>No committed item receipts for this batch.</p>
          )}
          {data.data?.map((i) => (
            <p key={i.id}>
              {i.kind} · {i.recordId} · {i.status} · {i.reason} · {i.recipients}{" "}
              current recipients
            </p>
          ))}
        </>
      )}
    </details>
  );
}
