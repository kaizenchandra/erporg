"use client";
import { useEffect, useState } from "react";
import {
  Avatar,
  Badge,
  Empty,
  Modal,
  formatDate,
} from "@/components/ui/primitives";
import { changeFieldLabels } from "./field-labels";
import { WithdrawRequest } from "./withdraw-request";
import { Icon } from "@/components/ui/icon";
import { api } from "@/lib/api";
import type { Actor, ChangeRequest, PeopleSummary } from "@/lib/contracts";
export function Approvals({
  revision,
  pending,
  actor,
  onSaved,
}: {
  revision: PeopleSummary;
  pending: number;
  actor: Actor;
  onSaved: (message?: string) => void;
}) {
  const [filter, setFilter] = useState("SUBMITTED"),
    [active, setActive] = useState<ChangeRequest | null>(null),
    [decision, setDecision] = useState("APPROVED"),
    [comment, setComment] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const [withdrawing, setWithdrawing] = useState<ChangeRequest | null>(null);
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{
    key: string;
    items?: ChangeRequest[];
    total?: number;
    error?: string;
  }>();
  const key = `${filter}:${page}:${retry}`;
  useEffect(() => {
    const controller = new AbortController();
    api<{ items: ChangeRequest[]; total: number }>(
      `/api/v1/request-inbox?status=${filter}&page=${page}&size=12`,
      {
        signal: AbortSignal.any([
          controller.signal,
          AbortSignal.timeout(10000),
        ]),
      },
    )
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, ...data });
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              err instanceof Error
                ? err.message
                : "Unable to load requests. Please retry.",
          });
      });
    return () => controller.abort();
  }, [filter, page, key, revision]);
  const current = result?.key === key ? result : undefined;
  const rows = current?.items ?? [];
  const total = current?.total ?? 0;
  const loading = !current;

  return (
    <>
      <div className="inbox-filters">
        <button
          className={filter === "SUBMITTED" ? "active" : ""}
          onClick={() => {
            setFilter("SUBMITTED");
            setPage(0);
          }}
        >
          Pending review <span>{pending}</span>
        </button>
        <button
          className={filter === "ALL" ? "active" : ""}
          onClick={() => {
            setFilter("ALL");
            setPage(0);
          }}
        >
          All requests
        </button>
        <button
          className={filter === "WITHDRAWN" ? "active" : ""}
          onClick={() => {
            setFilter("WITHDRAWN");
            setPage(0);
          }}
        >
          Withdrawn
        </button>
      </div>
      <section className="inbox-list" aria-busy={loading}>
        {loading && <p role="status">Loading requests…</p>}
        {current?.error && (
          <div role="alert">
            {current.error}{" "}
            <button onClick={() => setRetry((n) => n + 1)}>Retry inbox</button>
          </div>
        )}
        {rows.length ? (
          rows.map((r) => (
            <article className="request-card" key={r.id}>
              <Avatar name={r.employeeName} />
              <div className="request-content">
                <div>
                  <strong>{r.employeeName}</strong>
                  <Badge status={r.status} />
                </div>
                <h3>{changeFieldLabels[r.field] ?? r.field} change</h3>
                <p>
                  {r.oldValue ||
                    (r.field === "positionCode"
                      ? "Unassigned"
                      : "Not provided")}
                  <Icon name="arrow" size={14} />
                  <b>
                    {r.newValue ||
                      (r.field === "positionCode"
                        ? "Unassign position"
                        : "Remove value")}
                  </b>
                </p>
                <small>
                  Effective {formatDate(r.effectiveDate)} · Requested by{" "}
                  {r.requestedBy}
                </small>
                {r.comment && (
                  <p className="decision-comment">Decision: {r.comment}</p>
                )}
              </div>
              <button
                className="button secondary"
                onClick={() => {
                  setActive(r);
                  setComment("");
                  setError("");
                  setDecision("APPROVED");
                }}
              >
                View request
                <Icon name="chevron" size={15} />
              </button>
              {r.status === "SUBMITTED" && r.requestedBy === actor.subject && (
                <button
                  className="button secondary"
                  onClick={() => setWithdrawing(r)}
                >
                  Withdraw request
                </button>
              )}
            </article>
          ))
        ) : !loading && !current?.error ? (
          <Empty title="You’re all caught up">
            Requests awaiting your review will appear here.
          </Empty>
        ) : null}
        <footer className="table-footer">
          <span>
            {total} matching requests · Page {page + 1}
          </span>
          <div>
            <button
              className="button secondary"
              disabled={page === 0 || loading}
              onClick={() => setPage((p) => p - 1)}
            >
              Previous requests
            </button>
            <button
              className="button secondary"
              disabled={loading || !!current?.error || (page + 1) * 12 >= total}
              onClick={() => setPage((p) => p + 1)}
            >
              Next requests
            </button>
          </div>
        </footer>
      </section>
      {withdrawing && (
        <WithdrawRequest
          request={withdrawing}
          onClose={() => setWithdrawing(null)}
          onSaved={() => {
            setWithdrawing(null);
            setFilter("WITHDRAWN");
            setPage(0);
            setRetry((n) => n + 1);
            onSaved("Request withdrawn. No employee change was applied.");
          }}
        />
      )}
      {active && (
        <Modal
          title="Review employee change"
          onClose={() => {
            if (!busy) setActive(null);
          }}
        >
          <form
            className="change-form"
            onSubmit={async (e) => {
              e.preventDefault();
              if (busy) return;
              setBusy(true);
              setError("");
              try {
                await api(`/api/v1/requests/${active.id}/decision`, {
                  method: "POST",
                  body: JSON.stringify({ decision, comment }),
                });
                setActive(null);
                setPage(0);
                setRetry((n) => n + 1);
                onSaved();
              } catch (err) {
                setError(err instanceof Error ? err.message : "Please retry.");
              } finally {
                setBusy(false);
              }
            }}
          >
            <div className="form-person">
              <Avatar name={active.employeeName} />
              <div>
                <strong>{active.employeeName}</strong>
                <small>
                  {active.employeeId} ·{" "}
                  {changeFieldLabels[active.field] ?? active.field}
                </small>
              </div>
            </div>
            <div className="change-comparison">
              <div>
                <span>Current</span>
                <strong>
                  {active.oldValue ||
                    (active.field === "positionCode"
                      ? "Unassigned"
                      : "Not provided")}
                </strong>
              </div>
              <Icon name="arrow" />
              <div>
                <span>Proposed</span>
                <strong>
                  {active.newValue ||
                    (active.field === "positionCode"
                      ? "Unassign position"
                      : "Remove value")}
                </strong>
              </div>
            </div>
            <dl className="review-details">
              <div>
                <dt>Effective date</dt>
                <dd>{formatDate(active.effectiveDate)}</dd>
              </div>
              <div>
                <dt>Reason</dt>
                <dd>{active.reason}</dd>
              </div>
              <div>
                <dt>Requested by</dt>
                <dd>{active.requestedBy}</dd>
              </div>
            </dl>
            {active.status === "SUBMITTED" &&
            (!["name", "managerId"].includes(active.field) ||
              actor.role === "HR") &&
            active.requestedBy !== actor.subject &&
            active.employeeId !== actor.employeeId &&
            actor.role !== "EMPLOYEE" ? (
              <>
                {error && (
                  <div className="error" role="alert">
                    {error}
                  </div>
                )}
                <label>
                  Decision
                  <select
                    aria-label="Decision"
                    value={decision}
                    onChange={(e) => setDecision(e.target.value)}
                  >
                    <option value="APPROVED">Approve change</option>
                    <option value="REJECTED">Reject change</option>
                  </select>
                </label>
                <label>
                  Decision comment <span>*</span>
                  <textarea
                    required
                    maxLength={500}
                    rows={3}
                    value={comment}
                    onChange={(e) => setComment(e.target.value)}
                    placeholder="Record the reason for your decision"
                  />
                </label>
                <div className="quiet-note">
                  <Icon name="shield" />
                  <p>
                    {decision === "APPROVED"
                      ? "This updates one employee on the effective date and records your approval in the audit history. Reporting-manager changes also change team access."
                      : "The employee record will stay unchanged. The requester will be notified."}
                  </p>
                </div>
                <div className="form-footer">
                  <button
                    type="button"
                    className="button secondary"
                    onClick={() => setActive(null)}
                    disabled={busy}
                  >
                    Cancel
                  </button>
                  <button className="button primary" disabled={busy}>
                    {busy
                      ? "Saving decision…"
                      : decision === "APPROVED"
                        ? "Confirm approval"
                        : "Confirm rejection"}
                  </button>
                </div>
              </>
            ) : (
              <div className="quiet-note">
                <Icon name="shield" />
                <p>
                  {active.status !== "SUBMITTED"
                    ? `This request is ${active.status.toLowerCase()}. ${active.comment ?? ""}`
                    : "This request needs an independent, authorised reviewer. You can track its status here."}
                </p>
              </div>
            )}
          </form>
        </Modal>
      )}
    </>
  );
}
