"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Modal, formatDate } from "@/components/ui/primitives";
import type { Actor } from "@/lib/contracts";
export type Department = {
  code: string;
  name: string;
  parentCode: string | null;
  costCentre: string;
  effectiveFrom: string;
};
export type DepartmentAudit = {
  code: string;
  actor: string;
  reason: string;
  recordedAt: string;
  effectiveFrom: string;
  correlationId: string;
};
export function DepartmentReference({ actor }: { actor: Actor }) {
  const [departments, setDepartments] = useState<Department[]>([]);
  const [history, setHistory] = useState<DepartmentAudit[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [revision, setRevision] = useState(0);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    const options = {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    };
    Promise.all([
      api<Department[]>("/api/v1/organisation/departments", options),
      actor.role === "HR"
        ? api<DepartmentAudit[]>("/api/v1/organisation/history", options)
        : Promise.resolve([]),
    ])
      .then(([items, audit]) => {
        if (!controller.signal.aborted) {
          setDepartments(items);
          setHistory(audit);
          setError("");
        }
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setError(
            err instanceof Error ? err.message : "Unable to load departments.",
          );
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [actor.role, revision]);
  return (
    <section aria-label="Department reference" className="department-reference">
      <div className="section-heading">
        <h3>Departments & cost centres</h3>
        {actor.role === "HR" && (
          <button className="button primary" onClick={() => setCreating(true)}>
            Create department
          </button>
        )}
      </div>
      <p className="muted">
        Department codes and names are fixed. DEMO cost-centre mappings require
        finance configuration. Future departments are available for changes on
        or after their start date.
      </p>
      {notice && <p role="status">{notice}</p>}
      {loading && <p role="status">Loading department references…</p>}
      {error && (
        <div className="error" role="alert">
          {error}
          <button onClick={() => setRevision((n) => n + 1)}>
            Retry departments
          </button>
        </div>
      )}
      <div className="detail-grid">
        {departments.map((d) => (
          <article key={d.code}>
            <h4>{d.name}</h4>
            <p>
              {d.code} · Cost centre {d.costCentre}
            </p>
            <p>
              Parent:{" "}
              {departments.find((p) => p.code === d.parentCode)?.name ??
                "Root department"}
            </p>
            <p>
              {d.effectiveFrom > new Date().toISOString().slice(0, 10)
                ? "Planned"
                : "Effective"}{" "}
              · {formatDate(d.effectiveFrom)}
            </p>
          </article>
        ))}
      </div>
      {actor.role === "HR" && (
        <details>
          <summary>Department configuration history</summary>
          {history.map((h, i) => (
            <article className="history-event" key={`${h.code}-${i}`}>
              <div>
                <strong>{h.code} · Created</strong>
                <p>{h.reason}</p>
                <small>
                  {h.actor} · {new Date(h.recordedAt).toISOString()} · Effective{" "}
                  {formatDate(h.effectiveFrom)}
                </small>
              </div>
            </article>
          ))}
        </details>
      )}
      {creating && (
        <CreateDepartmentForm
          departments={departments}
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setNotice("Department created and audit recorded.");
            setRevision((n) => n + 1);
          }}
        />
      )}
    </section>
  );
}
function CreateDepartmentForm({
  departments,
  onClose,
  onSaved,
}: {
  departments: Department[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [code, setCode] = useState(""),
    [name, setName] = useState(""),
    [parentCode, setParent] = useState(""),
    [costCentre, setCostCentre] = useState(""),
    [effectiveFrom, setDate] = useState(new Date().toISOString().slice(0, 10)),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  const [key] = useState(() => crypto.randomUUID());
  const dirty =
    !!(code || name || parentCode || costCentre || reason) ||
    effectiveFrom !== new Date().toISOString().slice(0, 10);
  const close = () => {
    if (!busy && (!dirty || window.confirm("Discard this unsaved department?")))
      onClose();
  };
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  return (
    <Modal title="Create department" onClose={close}>
      <form
        className="change-form"
        onSubmit={async (event) => {
          event.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          try {
            await api("/api/v1/organisation/departments", {
              method: "POST",
              body: JSON.stringify({
                code,
                name,
                parentCode: parentCode || null,
                costCentre,
                effectiveFrom,
                reason,
                idempotencyKey: key,
              }),
            });
            onSaved();
          } catch (err) {
            setError(
              err instanceof Error
                ? err.message
                : "Unable to create department. Retry with your entered values.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          Creating a department configures the organisation. Employee transfers
          still need independent approval.
        </p>
        {error && (
          <div className="error" role="alert">
            {error} Your entries are preserved.
          </div>
        )}
        <label>
          Department code <span>*</span>
          <input
            required
            maxLength={30}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="PLATFORM"
          />
        </label>
        <label>
          Department name <span>*</span>
          <input
            required
            maxLength={120}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        </label>
        <label>
          Parent department
          <select
            value={parentCode}
            onChange={(e) => setParent(e.target.value)}
          >
            <option value="">Root department</option>
            {departments.map((d) => (
              <option key={d.code} value={d.code}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Cost-centre code <span>*</span>
          <input
            required
            maxLength={30}
            value={costCentre}
            onChange={(e) => setCostCentre(e.target.value)}
            placeholder="CC-PLATFORM"
          />
        </label>
        <label>
          Department start date <span>*</span>
          <input
            required
            type="date"
            min={new Date().toISOString().slice(0, 10)}
            value={effectiveFrom}
            onChange={(e) => setDate(e.target.value)}
          />
        </label>
        <label>
          Configuration reason <span>*</span>
          <textarea
            required
            rows={3}
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            disabled={busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "Creating…" : "Create department"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
