"use client";
import {
  createContext,
  useContext,
  useEffect,
  useId,
  useState,
  type ReactNode,
} from "react";
import {
  useTimeCommand,
  useTimeRead,
  TimePager,
} from "@/features/timekeeping/shared";
import { FinancialError } from "@/features/finance/shared";
import type { TalentRecord, Worker } from "./contracts";
export { useTimeRead as useTalentRead, TimePager as TalentPager };
export type Saved = (record?: TalentRecord) => void;
const Validation = createContext<Record<string, string>>({});
function useFieldError(name: string) {
  const fields = useContext(Validation);
  const id = useId();
  return {
    error: fields[name] ?? fields[name.replace(/\d+$/, " ").trim()],
    id,
  };
}
export function Field({
  name,
  label,
  type = "text",
  required = true,
  value,
  max = 180,
}: {
  name: string;
  label: string;
  type?: string;
  required?: boolean;
  value?: string;
  max?: number;
}) {
  const { error, id } = useFieldError(name);
  return (
    <label>
      {label}
      {required ? " *" : ""}
      <input
        name={name}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? id : undefined}
        type={type}
        required={required}
        defaultValue={value}
        maxLength={max}
        step={type === "number" ? "1" : undefined}
      />
      {error && (
        <span className="field-error" id={id}>
          {error}
        </span>
      )}
    </label>
  );
}
export function Notes({
  name,
  label,
  value,
  required = true,
}: {
  name: string;
  label: string;
  value?: string;
  required?: boolean;
}) {
  const { error, id } = useFieldError(name);
  return (
    <label>
      {label}
      {required ? " *" : ""}
      <textarea
        name={name}
        required={required}
        defaultValue={value}
        maxLength={3000}
        rows={3}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? id : undefined}
      />
      {error && (
        <span className="field-error" id={id}>
          {error}
        </span>
      )}
    </label>
  );
}
export function WorkerField({
  workers,
  name = "employeeId",
  label = "Worker",
  selected,
}: {
  workers: Worker[];
  name?: string;
  label?: string;
  selected?: string;
}) {
  const { error, id } = useFieldError(name);
  return (
    <label>
      {label} *
      <select
        name={name}
        required
        defaultValue={selected ?? ""}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? id : undefined}
      >
        <option value="" disabled>
          Choose a worker
        </option>
        {workers.map((w) => (
          <option value={w.id} key={w.id}>
            {w.name} · {w.id} · {w.status}
          </option>
        ))}
      </select>
      {error && (
        <span className="field-error" id={id}>
          {error}
        </span>
      )}
    </label>
  );
}
export function Currency() {
  return (
    <label>
      Currency *
      <select name="currency">
        <option>USD</option>
        <option>EUR</option>
        <option>GBP</option>
        <option>INR</option>
      </select>
    </label>
  );
}
export function value(f: FormData, k: string) {
  return String(f.get(k) ?? "");
}
export function TalentForm({
  url,
  build,
  onSaved,
  children,
  label,
  confirm,
  disabled = false,
}: {
  url: string;
  build: (f: FormData) => Record<string, unknown>;
  onSaved: Saved;
  children: ReactNode;
  label: string;
  confirm?: string;
  disabled?: boolean;
}) {
  const c = useTimeCommand();
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    if (!dirty) return;
    const leave = (e: BeforeUnloadEvent) => e.preventDefault();
    const navigate = (e: Event) => {
      if (!window.confirm("Discard unsaved talent changes?"))
        e.preventDefault();
    };
    window.addEventListener("beforeunload", leave);
    window.addEventListener("atlas-before-navigation", navigate);
    return () => {
      window.removeEventListener("beforeunload", leave);
      window.removeEventListener("atlas-before-navigation", navigate);
    };
  }, [dirty]);
  return (
    <form
      className="talent-form"
      onChange={() => setDirty(true)}
      onSubmit={async (e) => {
        e.preventDefault();
        const data = build(new FormData(e.currentTarget));
        if (confirm && !window.confirm(confirm)) return;
        const r = await c.run<TalentRecord>(url, data);
        if (r) {
          setDirty(false);
          onSaved(r);
        }
      }}
    >
      <FinancialError message={c.error} fields={c.fields} />
      <Validation.Provider value={c.fields}>
        <fieldset disabled={c.busy || disabled}>
          {children}
          <Notes name="reason" label="Reason for this action" />
          <button className="button primary" disabled={c.busy || disabled}>
            {c.busy ? "Saving…" : label}
          </button>
        </fieldset>
      </Validation.Provider>
    </form>
  );
}
export function Decision({
  kind,
  record,
  actions,
  onSaved,
}: {
  kind: string;
  record: TalentRecord;
  actions: string[];
  onSaved: Saved;
}) {
  return actions.length ? (
    <TalentForm
      url={`/api/v1/talent/${kind}/${record.meta.id}/actions`}
      build={(f) => ({
        version: record.meta.version,
        action: value(f, "action"),
        reason: value(f, "reason"),
      })}
      onSaved={onSaved}
      label="Confirm workflow action"
      confirm={`Apply this action to one record at revision ${record.meta.version}? Approval may create a durable downstream record.`}
    >
      <label>
        Workflow action *
        <select name="action">
          {actions.map((a) => (
            <option key={a}>{a}</option>
          ))}
        </select>
      </label>
    </TalentForm>
  ) : null;
}
