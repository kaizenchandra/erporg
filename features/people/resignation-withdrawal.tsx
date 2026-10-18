"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import type {
  Resignation,
  ResignationWithdrawal,
} from "./resignation-contracts";

export function ResignationWithdrawalPanel({
  request,
  actor,
  onDirty,
  onBusy,
  onSaved,
}: {
  request: Resignation;
  actor: Actor;
  onDirty: (v: boolean) => void;
  onBusy: (v: boolean) => void;
  onSaved: () => void;
}) {
  const [context, setContext] = useState<{
      receipt: ResignationWithdrawal | null;
    } | null>(null),
    [editing, setEditing] = useState(false),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false),
    [revision, setRevision] = useState(0);
  const key = useRef(crypto.randomUUID()),
    inFlight = useRef(false),
    summary = useRef<HTMLDivElement>(null),
    input = useRef<HTMLTextAreaElement>(null),
    action = useRef<HTMLButtonElement>(null);
  const path = `/api/v1/resignations/${request.id}/withdrawal`;
  useEffect(() => {
    const c = new AbortController();
    api<{ receipt: ResignationWithdrawal | null }>(path, { signal: c.signal })
      .then((r) => {
        if (!c.signal.aborted) setContext(r);
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setError(
            e instanceof Error
              ? e.message
              : "Unable to load withdrawal receipt.",
          );
      });
    return () => c.abort();
  }, [path, revision]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const canWithdraw =
    request.status === "SUBMITTED" &&
    request.requestedBy === actor.subject &&
    request.employeeId === actor.employeeId &&
    !context?.receipt;
  return (
    <section
      className="rehire-access-panel"
      aria-label="Resignation withdrawal"
    >
      {error && (
        <div role="alert" tabIndex={-1} ref={summary} className="error">
          <p>{error}</p>
          {editing && (
            <a
              href="#resignation-withdrawal-reason"
              onClick={() => input.current?.focus()}
            >
              Review the withdrawal reason
            </a>
          )}
          <p>
            Values are preserved. Close and reopen for the current request
            status before retrying an uncertain outcome.
          </p>
          {!context && (
            <button
              type="button"
              className="button secondary"
              onClick={() => {
                setError("");
                setRevision((v) => v + 1);
              }}
            >
              Retry withdrawal receipt
            </button>
          )}
        </div>
      )}
      {!context ? (
        <p role="status">Loading withdrawal receipt…</p>
      ) : context.receipt ? (
        <article>
          <h3>Withdrawn before review</h3>
          <p>{context.receipt.reason}</p>
          <p>
            By {context.receipt.actor} ·{" "}
            {new Date(context.receipt.recordedAt).toLocaleString()}
          </p>
          <p>Correlation: {context.receipt.correlationId}</p>
          <p>
            No exit coordination was created. The original resignation and this
            receipt are retained.
          </p>
        </article>
      ) : canWithdraw ? (
        <>
          {!editing ? (
            <button
              type="button"
              ref={action}
              className="button secondary"
              onClick={() => {
                setEditing(true);
                setError("");
                key.current = crypto.randomUUID();
                onDirty(true);
                requestAnimationFrame(() => input.current?.focus());
              }}
            >
              Withdraw pending resignation
            </button>
          ) : (
            <>
              <h3>Withdraw this pending resignation</h3>
              <p>
                This withdraws only your unreviewed request for{" "}
                {request.lastWorkingDate}. Employment and access stay unchanged.
                No exit handoff is created; the original request and reason
                remain in private history. You may submit a new request. An HR
                decision recorded first prevents withdrawal.
              </p>
              <label htmlFor="resignation-withdrawal-reason">
                Withdrawal reason (required)
              </label>
              <textarea
                id="resignation-withdrawal-reason"
                ref={input}
                required
                maxLength={500}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                aria-invalid={!!error}
              />
              <div className="form-actions">
                <button
                  type="button"
                  className="button secondary"
                  disabled={busy}
                  onClick={() => {
                    setEditing(false);
                    setReason("");
                    setError("");
                    onDirty(false);
                    requestAnimationFrame(() => action.current?.focus());
                  }}
                >
                  Keep pending request
                </button>
                <button
                  type="button"
                  className="button primary"
                  disabled={busy}
                  onClick={async () => {
                    if (inFlight.current) return;
                    if (!reason.trim() || reason.length > 500) {
                      setError(
                        "Enter a withdrawal reason of 1–500 characters. Nothing has been withdrawn.",
                      );
                      return;
                    }
                    inFlight.current = true;
                    setBusy(true);
                    onBusy(true);
                    setError("");
                    try {
                      const receipt = await api<ResignationWithdrawal>(path, {
                        method: "POST",
                        body: JSON.stringify({
                          reason,
                          version: request.version,
                          idempotencyKey: key.current,
                        }),
                      });
                      setContext({ receipt });
                      setEditing(false);
                      setReason("");
                      onDirty(false);
                      onSaved();
                    } catch (e: unknown) {
                      setError(
                        e instanceof Error
                          ? e.message
                          : "Withdrawal outcome could not be confirmed.",
                      );
                    } finally {
                      inFlight.current = false;
                      setBusy(false);
                      onBusy(false);
                    }
                  }}
                >
                  {busy ? "Withdrawing…" : "Confirm withdrawal before review"}
                </button>
              </div>
            </>
          )}
        </>
      ) : (
        <p>
          Only the original requester can withdraw a pending request. Approved
          and rejected requests cannot be withdrawn here.
        </p>
      )}
    </section>
  );
}
