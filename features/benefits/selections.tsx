"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { WithdrawSelectionForm } from "./withdraw-selection";
import { Modal, formatDate } from "@/components/ui/primitives";
export type SelectablePlan = {
  code: string;
  name: string;
  currency: string;
  monthlyContribution: string;
};
type Dependant = { name: string; relationship: string };
export type Selection = {
  requestedBy: string;
  currency: string;
  monthlyContribution: string;
  id: string;
  employeeId: string;
  planCode: string;
  planName: string;
  status: string;
  version: number;
  effectiveDate: string;
  reason: string;
  ruleVersion: string;
  dependants: Dependant[];
  handoffStatus: string | null;
  history: {
    action: string;
    actor: string;
    reason: string;
    recordedAt: string;
    correlationId: string;
  }[];
};
export type SelectionPage = {
  items: Selection[];
  total: number;
  page: number;
  size: number;
};
export function BenefitSelections({
  actor,
  plan,
  onPlanClose,
}: {
  actor: Actor;
  plan: SelectablePlan | null;
  onPlanClose: () => void;
}) {
  const [withdrawing, setWithdrawing] = useState<Selection | null>(null);
  const heading = useRef<HTMLHeadingElement>(null),
    restoreFocus = useRef(false);
  const [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [review, setReview] = useState<Selection | null>(null),
    [notice, setNotice] = useState("");
  const key = JSON.stringify([
    actor.subject,
    actor.tenant,
    actor.role,
    page,
    revision,
  ]);
  const [result, setResult] = useState<{
    key: string;
    data?: SelectionPage;
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    const controller = new AbortController();
    api<SelectionPage>(`/api/v1/benefits/selections?page=${page}&size=8`, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Unable to load selections.",
          });
      });
    return () => controller.abort();
  }, [key, page]);
  useEffect(() => {
    if (current?.data && restoreFocus.current) {
      restoreFocus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  const saved = () => {
    onPlanClose();
    setReview(null);
    setNotice(
      "Benefit selection saved. Approved deductions remain pending payroll; no payment was made.",
    );
    setRevision((n) => n + 1);
  };
  return (
    <section aria-label="Benefit selections">
      <h3 ref={heading} tabIndex={-1}>
        {actor.role === "HR"
          ? "Benefit review and selections"
          : "Your benefit selections"}
      </h3>
      <p className="muted">
        Private dependant details are visible to the employee and HR. Approval
        creates a pending payroll handoff; it does not enrol with a provider or
        run payroll.
      </p>
      {notice && <p role="status">{notice}</p>}
      {!current ? (
        <p role="status">Loading benefit selections…</p>
      ) : current.error ? (
        <p role="alert" className="error">
          {current.error}{" "}
          <button onClick={() => setRevision((n) => n + 1)}>
            Retry selections
          </button>
        </p>
      ) : (
        <>
          <p>
            {current.data!.total} selections · Page {page + 1}
          </p>
          {current.data!.items.length ? (
            current.data!.items.map((s) => (
              <article className="history-event" key={s.id}>
                <div>
                  <h4>
                    {s.planName} · {s.employeeId}
                  </h4>
                  <p>
                    <strong>{s.status}</strong> · {s.currency}{" "}
                    {s.monthlyContribution} monthly · Effective{" "}
                    {formatDate(s.effectiveDate)}
                  </p>
                  <p>{s.reason}</p>
                  <p>Payroll handoff: {s.handoffStatus ?? "Not created"}</p>
                  <details>
                    <summary>Dependants and selection history</summary>
                    {s.dependants.length ? (
                      s.dependants.map((d, i) => (
                        <p key={i}>
                          {d.name} · {d.relationship}
                        </p>
                      ))
                    ) : (
                      <p>No dependants selected.</p>
                    )}
                    <p>
                      Rule {s.ruleVersion}: fixed employee amount, no dependant
                      surcharge or proration.
                    </p>
                    {s.history.map((h, i) => (
                      <p key={i}>
                        {h.action} · {h.actor} · {h.reason} ·{" "}
                        {new Date(h.recordedAt).toISOString()} · Correlation:{" "}
                        {h.correlationId}
                      </p>
                    ))}
                  </details>
                  {s.status === "PENDING" &&
                    s.employeeId === actor.employeeId &&
                    s.requestedBy === actor.subject && (
                      <button
                        className="button secondary"
                        onClick={() => setWithdrawing(s)}
                      >
                        Withdraw benefit selection
                      </button>
                    )}
                  {actor.role === "HR" &&
                    s.status === "PENDING" &&
                    s.employeeId !== actor.employeeId &&
                    s.requestedBy !== actor.subject && (
                      <button
                        className="button primary"
                        onClick={() => setReview(s)}
                      >
                        Review benefit selection
                      </button>
                    )}
                </div>
              </article>
            ))
          ) : (
            <p>No benefit selections yet. Choose an eligible plan to start.</p>
          )}
          <div className="position-pagination">
            <button
              className="button secondary"
              disabled={page === 0}
              onClick={() => setPage((n) => n - 1)}
            >
              Previous selections
            </button>
            <button
              className="button secondary"
              disabled={(page + 1) * 8 >= current.data!.total}
              onClick={() => setPage((n) => n + 1)}
            >
              Next selections
            </button>
          </div>
        </>
      )}
      {withdrawing && (
        <WithdrawSelectionForm
          selection={withdrawing}
          onClose={() => {
            restoreFocus.current = true;
            setWithdrawing(null);
            setRevision((n) => n + 1);
          }}
          onSaved={() => {
            restoreFocus.current = true;
            setWithdrawing(null);
            setNotice(
              "Benefit selection withdrawn. History is retained; you may submit a fresh eligible selection.",
            );
            setPage(0);
            setRevision((n) => n + 1);
          }}
        />
      )}
      {(plan || review) && (
        <BenefitSelectionForm
          plan={plan}
          review={review}
          onClose={() => {
            onPlanClose();
            setReview(null);
          }}
          onSaved={saved}
        />
      )}
    </section>
  );
}
export function BenefitSelectionForm({
  plan,
  review,
  onClose,
  onSaved,
}: {
  plan: SelectablePlan | null;
  review: Selection | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [dependants, setDependants] = useState<Dependant[]>([]),
    [reason, setReason] = useState(""),
    [outcome, setOutcome] = useState("APPROVED"),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const [key] = useState(() => crypto.randomUUID()),
    id = useId(),
    summary = useRef<HTMLDivElement>(null);
  const dirty = !!reason || dependants.length > 0 || outcome !== "APPROVED";
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard this unsaved benefit selection?"))
    )
      onClose();
  };
  return (
    <Modal
      title={
        review ? "Review benefit selection" : "Select demonstration benefit"
      }
      onClose={close}
    >
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFields({});
          try {
            await api(
              review
                ? `/api/v1/benefits/selections/${review.id}/decision`
                : "/api/v1/benefits/selections",
              {
                method: "POST",
                body: JSON.stringify(
                  review
                    ? {
                        version: review.version,
                        outcome,
                        reason,
                        idempotencyKey: key,
                      }
                    : {
                        planCode: plan!.code,
                        dependants,
                        reason,
                        idempotencyKey: key,
                      },
                ),
              },
            );
            onSaved();
          } catch (e) {
            setError(
              e instanceof Error ? e.message : "Unable to save selection.",
            );
            setFields(e instanceof ApiError ? e.fieldErrors : {});
          } finally {
            setBusy(false);
          }
        }}
      >
        <p id={`${id}-planCode`} tabIndex={-1}>
          {review?.planName ?? plan!.name} · {(review ?? plan)!.currency}{" "}
          {(review ?? plan)!.monthlyContribution} monthly employee contribution.
        </p>
        <p>
          Demonstration rule: no additional dependant contribution or proration.{" "}
          {review
            ? `Applies to one selection for ${review.employeeId}. Approval queues one deduction for future payroll processing; rejection creates none.`
            : "Starts on the UTC submission date after independent HR approval. No provider enrolment or payment occurs."}
        </p>
        {error && (
          <div
            role="alert"
            aria-label="Benefit selection errors"
            className="error"
            tabIndex={-1}
            ref={summary}
          >
            {error} Your entries are preserved. Correct the fields and retry;
            refresh selections after a conflict.
            <ul>
              {Object.entries(fields).map(([f, m]) => (
                <li key={f}>
                  <a
                    href={`#${id}-${f}`}
                    onClick={(e) => {
                      e.preventDefault();
                      const target = document.getElementById(`${id}-${f}`);
                      if (target) target.focus();
                      else summary.current?.focus();
                    }}
                  >
                    {m}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        {review ? (
          <>
            <p>{review.reason}</p>
            {review.dependants.map((d, i) => (
              <p key={i}>
                {d.name} · {d.relationship}
              </p>
            ))}
            <label>
              Benefit decision *
              <select
                id={`${id}-outcome`}
                aria-invalid={!!fields.outcome}
                value={outcome}
                onChange={(e) => setOutcome(e.target.value)}
              >
                <option value="APPROVED">Approve</option>
                <option value="REJECTED">Reject</option>
              </select>
            </label>
          </>
        ) : (
          <fieldset id={`${id}-dependants`} tabIndex={-1}>
            <legend>Dependants (optional, maximum five)</legend>
            {dependants.map((d, i) => (
              <div className="change-form" key={i}>
                <label>
                  Dependant {i + 1} name *
                  <input
                    required
                    maxLength={120}
                    value={d.name}
                    onChange={(e) =>
                      setDependants((list) =>
                        list.map((v, n) =>
                          n === i ? { ...v, name: e.target.value } : v,
                        ),
                      )
                    }
                  />
                </label>
                <label>
                  Dependant {i + 1} relationship *
                  <select
                    value={d.relationship}
                    onChange={(e) =>
                      setDependants((list) =>
                        list.map((v, n) =>
                          n === i ? { ...v, relationship: e.target.value } : v,
                        ),
                      )
                    }
                  >
                    <option value="CHILD">Child</option>
                    <option value="PARTNER">Partner</option>
                    <option value="OTHER">Other</option>
                  </select>
                </label>
                <button
                  type="button"
                  onClick={() =>
                    setDependants((list) => list.filter((_, n) => n !== i))
                  }
                >
                  Remove dependant {i + 1}
                </button>
              </div>
            ))}
            {fields.dependants && <p className="error">{fields.dependants}</p>}
            <button
              type="button"
              disabled={dependants.length >= 5}
              onClick={() =>
                setDependants((list) => [
                  ...list,
                  { name: "", relationship: "CHILD" },
                ])
              }
            >
              Add dependant
            </button>
          </fieldset>
        )}
        <label>
          Benefit selection reason *
          <textarea
            id={`${id}-reason`}
            required
            maxLength={500}
            rows={3}
            aria-invalid={!!fields.reason}
            aria-describedby={fields.reason ? `${id}-reason-error` : undefined}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {fields.reason && (
            <span id={`${id}-reason-error`} className="error">
              {fields.reason}
            </span>
          )}
        </label>
        <div className="form-footer">
          <button
            className="button secondary"
            type="button"
            disabled={busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy
              ? "Saving…"
              : review
                ? "Confirm benefit decision"
                : "Submit benefit selection"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
