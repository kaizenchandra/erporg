"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { formatDate } from "@/components/ui/primitives";
import type { LegalEntity } from "@/features/settings/contracts";
export type EntityAssignmentReview = {
  requestId: string;
  reference: LegalEntity | null;
  retirementBlocked: boolean;
};
export function EntityAssignmentContext({
  requestId,
  onReady,
}: {
  requestId: string;
  onReady: (id: string) => void;
}) {
  const [retry, setRetry] = useState(0);
  const [result, setResult] = useState<{
    key: string;
    value?: EntityAssignmentReview;
    error?: string;
  }>();
  const error = useRef<HTMLParagraphElement>(null);
  const key = JSON.stringify([requestId, retry]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    const c = new AbortController();
    onReady("");
    api<EntityAssignmentReview>(
      `/api/v1/requests/${encodeURIComponent(requestId)}/entity-context`,
      { signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]) },
    )
      .then((value) => {
        if (!c.signal.aborted) {
          setResult({ key, value });
          onReady(value.retirementBlocked ? "" : requestId);
        }
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error
                ? e.message
                : "Entity reference context unavailable.",
          });
      });
    return () => c.abort();
  }, [requestId, key, onReady]);
  useEffect(() => {
    if (current?.error) error.current?.focus();
  }, [current?.error]);
  return (
    <section aria-label="Declared entity assignment impact">
      <h3>Declared entity assignment impact</h3>
      {!current ? (
        <p role="status">Loading declared entity context…</p>
      ) : current.error ? (
        <p ref={error} role="alert" tabIndex={-1}>
          {current.error}
          <button type="button" onClick={() => setRetry((n) => n + 1)}>
            Retry entity context
          </button>
        </p>
      ) : (
        current.value && (
          <>
            {current.value.reference ? (
              <p>
                {current.value.reference.name} · {current.value.reference.code}{" "}
                · {current.value.reference.countryCode} ·{" "}
                {current.value.reference.currencyCode} ·{" "}
                {current.value.reference.timeZone} · effective from{" "}
                {formatDate(current.value.reference.effectiveFrom)}
              </p>
            ) : (
              <p>Explicit unassignment for the selected employment episode.</p>
            )}
            {current.value.retirementBlocked && (
              <p role="status">
                A pending or approved retirement blocks approval. Reject and
                resubmit with an available reference.
              </p>
            )}
            <p>
              This is supplied, unverified company-reference metadata. Approval
              affects this employee&apos;s reference on the effective date,
              preserves earlier values, and permanently blocks new retirement of
              the used reference. Only HR and the employee can inspect the
              field. No contract, payroll, tax, payment or statutory rule is
              activated.
            </p>
          </>
        )
      )}
    </section>
  );
}
