"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import {
  DepartmentDirectory,
  DepartmentParentPicker,
} from "./department-directory";
import { DepartmentParentDialog } from "./department-parent";
import { ReferenceHistory } from "./reference-history";
import type { Actor } from "@/lib/contracts";
export type Department = {
  code: string;
  name: string;
  parentCode: string | null;
  costCentre: string;
  effectiveFrom: string;
};
export function DepartmentReference({ actor }: { actor: Actor }) {
  const [restoreParentFocus, setRestoreParentFocus] = useState(false);
  const [parentCode, setParentCode] = useState<string | null>(null);
  const [savedCode, setSavedCode] = useState("");
  const [revision, setRevision] = useState(0);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState("");
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
        Department codes and names are fixed. HR can correct current reference
        parents with retained history. DEMO cost-centre mappings require finance
        configuration. Future departments are available for changes on or after
        their start date.
      </p>
      {notice && <p role="status">{notice}</p>}
      <DepartmentDirectory
        key={`${actor.tenant}:${actor.subject}:${actor.role}:${revision}`}
        searchCode={savedCode}
        focusOnLoad={restoreParentFocus}
        onLoadedFocus={() => setRestoreParentFocus(false)}
        onManageParent={actor.role === "HR" ? setParentCode : undefined}
      />
      {actor.role === "HR" && (
        <ReferenceHistory
          key={`department-history:${actor.tenant}:${actor.subject}:${actor.role}:${revision}`}
          kind="DEPARTMENT"
        />
      )}
      {parentCode && (
        <DepartmentParentDialog
          key={`${actor.subject}:${actor.tenant}:${actor.employeeId}:${parentCode}`}
          code={parentCode}
          onClose={() => setParentCode(null)}
          onSaved={() => {
            setParentCode(null);
            setRestoreParentFocus(true);
            setNotice(
              "Department parent corrected and immutable history recorded. Employee reporting and costing are unchanged.",
            );
            setRevision((n) => n + 1);
          }}
        />
      )}
      {creating && (
        <CreateDepartmentForm
          onClose={() => setCreating(false)}
          onSaved={(code) => {
            setSavedCode(code);
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
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved: (code: string) => void;
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
            onSaved(code);
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
        <DepartmentParentPicker value={parentCode} onChange={setParent} />
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
