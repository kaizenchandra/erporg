import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import type { Actor } from "@/lib/contracts";
import type { EmployeeDocument } from "./contracts";
import type { RetentionReview } from "./retention-contracts";
import { RetentionForm } from "./retention-form";
export function DocumentRetention({
  document: d,
  actor,
  onClose,
}: {
  document: EmployeeDocument;
  actor: Actor;
  onClose: () => void;
}) {
  const [revision, setRevision] = useState(0),
    [command, setCommand] = useState<{ action: string; holdId?: string }>(),
    [result, setResult] = useState<{
      key: string;
      data?: RetentionReview;
      error?: string;
    }>();
  const key = JSON.stringify([
    d.id,
    actor.subject,
    actor.tenant,
    actor.role,
    revision,
  ]);
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    const c = new AbortController();
    api<RetentionReview>(`/api/v1/documents/${d.id}/retention`, {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!c.signal.aborted) setResult({ key, data });
      })
      .catch((e: unknown) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Governance review unavailable.",
          });
      });
    return () => c.abort();
  }, [d.id, key]);
  if (command && current?.data)
    return (
      <RetentionForm
        key={`${command.action}-${command.holdId ?? ""}`}
        documentId={d.id}
        review={current.data!}
        action={command.action}
        holdId={command.holdId}
        onCancel={() => setCommand(undefined)}
        onSaved={() => {
          setCommand(undefined);
          setRevision((n) => n + 1);
        }}
      />
    );
  return (
    <Modal title="Retention and legal holds" onClose={onClose}>
      <div className="retention-review">
        {!current ? (
          <p role="status">Loading governance review…</p>
        ) : current.error ? (
          <p className="error" role="alert">
            {current.error}{" "}
            <button onClick={() => setRevision((n) => n + 1)}>
              Retry governance review
            </button>
          </p>
        ) : (
          <>
            <p>
              {d.filename} · Employee {current.data!.employeeId}. Applies to
              this entire document chain and its future versions. Private HR
              governance audit; no deletion or archive operation is available.
            </p>
            <p>
              <strong>{current.data!.state.replaceAll("_", " ")}</strong> · As
              of {current.data!.asOf} (UTC) · Governance version{" "}
              {current.data!.version}
            </p>
            <p>Retain until: {current.data!.retainUntil ?? "Not configured"}</p>
            {current.data!.basis && <p>Policy basis: {current.data!.basis}</p>}
            {current.data!.state === "REVIEW_DUE" && (
              <p>
                Review is due. This is not permission to delete; legal policy
                and archive/deletion controls remain unverified.
              </p>
            )}
            {current.data!.version < 100 ? (
              <div className="form-footer">
                <button
                  className="button secondary"
                  onClick={() => setCommand({ action: "SET_POLICY" })}
                >
                  Set retention directive
                </button>
                <button
                  className="button secondary"
                  onClick={() => setCommand({ action: "PLACE_HOLD" })}
                >
                  Place legal hold
                </button>
              </div>
            ) : (
              <p>
                Governance event limit reached. Contact document operations; no
                bypass is available.
              </p>
            )}
            <h3>Legal holds</h3>
            {!current.data!.holds.length ? (
              <p>No legal holds recorded.</p>
            ) : (
              current.data!.holds.map((h) => (
                <div className="history-event" key={h.id}>
                  <div>
                    <strong>
                      {h.caseReference} · {h.active ? "Active" : "Released"}
                    </strong>
                    <p>Placed by {h.placedBy}</p>
                    {h.active &&
                      (h.placedEmployeeId === actor.employeeId ||
                      h.placedBy === actor.subject ? (
                        <p>A different HR employee must release this hold.</p>
                      ) : (
                        current.data!.version < 100 && (
                          <button
                            className="button secondary"
                            onClick={() =>
                              setCommand({
                                action: "RELEASE_HOLD",
                                holdId: h.id,
                              })
                            }
                          >
                            Release hold for {h.caseReference}
                          </button>
                        )
                      ))}
                  </div>
                </div>
              ))
            )}
            <h3>Governance history</h3>
            {!current.data!.history.length ? (
              <p>
                No directive or hold has been recorded. No policy is inferred.
              </p>
            ) : (
              current.data!.history.map((e) => (
                <div className="history-event" key={e.version}>
                  <div>
                    <strong>
                      Event {e.version} · {e.action}
                    </strong>
                    <p>
                      {e.actor} · {e.reason} ·{" "}
                      {new Date(e.recordedAt).toISOString()}
                    </p>
                    {e.retainUntil && (
                      <p>
                        Retain until {e.retainUntil} · {e.basis}
                      </p>
                    )}
                    {e.holdId && (
                      <p>
                        Case:{" "}
                        {current.data!.holds.find((h) => h.id === e.holdId)
                          ?.caseReference ??
                          e.caseReference ??
                          "Unavailable"}
                      </p>
                    )}
                    <p>Correlation: {e.correlationId}</p>
                  </div>
                </div>
              ))
            )}
          </>
        )}
      </div>
    </Modal>
  );
}
