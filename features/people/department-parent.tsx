"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
import { DepartmentPicker } from "./department-picker";
export type ParentChange = {
  code: string;
  version: number;
  oldParentCode: string | null;
  parentCode: string | null;
  effectiveDate: string;
  recordedAt: string;
  actor: string;
  reason: string;
  idempotencyKey: string;
  correlationId: string;
};
export type ParentContext = {
  department: {
    code: string;
    name: string;
    parentCode: string | null;
    parentName: string | null;
    effectiveFrom: string;
    version: number;
  };
  evaluatedOn: string;
  items: ParentChange[];
  total: number;
  page: number;
  size: number;
};
export function DepartmentParentDialog({
  code,
  onClose,
  onSaved,
}: {
  code: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [initial, setInitial] = useState<ParentContext>(),
    [dirty, setDirty] = useState(false),
    [busy, setBusy] = useState(false);
  const [result, setResult] = useState<{
    key: string;
    data?: ParentContext;
    error?: string;
  }>();
  const key = JSON.stringify([code, page, revision]),
    current = result?.key === key ? result : undefined;
  const error = useRef<HTMLDivElement>(null),
    heading = useRef<HTMLHeadingElement>(null),
    focus = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    api<ParentContext>(
      `/api/v1/organisation/departments/${encodeURIComponent(code)}/hierarchy?page=${page}&size=10`,
      {
        signal: AbortSignal.any([
          controller.signal,
          AbortSignal.timeout(10000),
        ]),
      },
    )
      .then((data) => {
        if (!controller.signal.aborted) {
          setResult({ key, data });
          setInitial((old) => old ?? data);
        }
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Hierarchy context unavailable.",
          });
      });
    return () => controller.abort();
  }, [code, key, page]);
  useEffect(() => {
    if (current?.error) {
      focus.current = false;
      error.current?.focus();
    } else if (current?.data && focus.current) {
      focus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard this unsaved parent correction?"))
    )
      onClose();
  };
  const reload = () => {
    if (
      !busy &&
      (!dirty ||
        window.confirm(
          "Discard this draft and reload the current hierarchy revision?",
        ))
    ) {
      setInitial(undefined);
      setDirty(false);
      setPage(0);
      focus.current = true;
      setRevision((n) => n + 1);
    }
  };
  return (
    <Modal
      title="Department parent configuration"
      onClose={close}
      closeDisabled={busy}
    >
      <p className="department-parent-intro">
        Correct the reference hierarchy for one department on the server’s
        current UTC business date. Employee reporting lines, employment
        departments, positions and cost-centre mappings are unchanged. Future
        scheduling and approval routing are not part of this action.
      </p>
      {initial && (
        <ParentCorrectionForm
          key={`${code}:${initial.department.version}`}
          context={initial}
          onDirty={setDirty}
          onBusy={setBusy}
          onSaved={onSaved}
        />
      )}
      <section
        className="department-parent-history"
        aria-label="Department parent correction history"
      >
        <h3 ref={heading} tabIndex={-1}>
          Parent correction history
        </h3>
        <button
          type="button"
          className="button secondary"
          disabled={busy}
          onClick={reload}
        >
          Reload hierarchy context
        </button>
        {!current ? (
          <p role="status">Loading hierarchy context…</p>
        ) : current.error ? (
          <div role="alert" ref={error} tabIndex={-1}>
            {current.error} Your draft is preserved.{" "}
            <button
              type="button"
              onClick={() => {
                focus.current = true;
                setRevision((n) => n + 1);
              }}
            >
              Retry hierarchy history
            </button>
          </div>
        ) : (
          <>
            <p role="status">
              {current.data!.total} parent corrections · Page {page + 1} of{" "}
              {Math.max(1, Math.ceil(current.data!.total / 10))} · Evaluated{" "}
              {current.data!.evaluatedOn} (UTC)
            </p>
            {!current.data!.items.length && (
              <p>
                No parent corrections recorded. The original declaration remains
                authoritative.
              </p>
            )}
            {current.data!.items.map((c) => (
              <article className="history-event" key={c.version}>
                <h4>
                  Revision {c.version}: {c.oldParentCode ?? "Root department"} →{" "}
                  {c.parentCode ?? "Root department"}
                </h4>
                <p>{c.reason}</p>
                <p>
                  {c.actor} · Effective {c.effectiveDate} · Recorded{" "}
                  {new Date(c.recordedAt).toISOString()} (UTC)
                </p>
                <p>Correlation {c.correlationId}</p>
              </article>
            ))}
            <div className="change-history-actions">
              <button
                type="button"
                disabled={page === 0}
                onClick={() => {
                  focus.current = true;
                  setPage((n) => n - 1);
                }}
              >
                Previous parent corrections
              </button>
              <button
                type="button"
                disabled={(page + 1) * 10 >= current.data!.total}
                onClick={() => {
                  focus.current = true;
                  setPage((n) => n + 1);
                }}
              >
                Next parent corrections
              </button>
            </div>
          </>
        )}
      </section>
    </Modal>
  );
}
function ParentCorrectionForm({
  context,
  onDirty,
  onBusy,
  onSaved,
}: {
  context: ParentContext;
  onDirty: (value: boolean) => void;
  onBusy: (value: boolean) => void;
  onSaved: () => void;
}) {
  const [parent, setParent] = useState(context.department.parentCode ?? ""),
    [reason, setReason] = useState(""),
    [confirmed, setConfirmed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [fields, setFields] = useState<Record<string, string>>({});
  const [key] = useState(() => crypto.randomUUID());
  const id = useId(),
    summary = useRef<HTMLDivElement>(null);
  const dirty =
    parent !== (context.department.parentCode ?? "") || !!reason || confirmed;
  useEffect(() => {
    onDirty(dirty);
  }, [dirty, onDirty]);
  useEffect(() => {
    const guard = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [dirty]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const available = context.department.effectiveFrom <= context.evaluatedOn;
  return (
    <form
      className="change-form"
      noValidate
      onSubmit={async (e) => {
        e.preventDefault();
        if (busy || !available) return;
        setError("");
        setFields({});
        if (!confirmed) {
          setError("Confirm the scope and impact before saving.");
          return;
        }
        if (!Number.isSafeInteger(context.department.version)) {
          setError(
            "The revision cannot be represented safely. Contact your administrator before correcting.",
          );
          return;
        }
        setBusy(true);
        onBusy(true);
        try {
          await api(
            `/api/v1/organisation/departments/${encodeURIComponent(context.department.code)}/parent-changes`,
            {
              method: "POST",
              body: JSON.stringify({
                version: context.department.version,
                effectiveDate: context.evaluatedOn,
                parentCode: parent || null,
                reason,
                idempotencyKey: key,
              }),
            },
          );
          onSaved();
        } catch (e) {
          setError(e instanceof Error ? e.message : "Correction failed.");
          setFields(e instanceof ApiError ? e.fieldErrors : {});
        } finally {
          setBusy(false);
          onBusy(false);
        }
      }}
    >
      <h3>
        {context.department.name} ({context.department.code})
      </h3>
      <p>
        Loaded revision {context.department.version} · Original start{" "}
        {context.department.effectiveFrom}
      </p>
      <p>
        Current parent:{" "}
        {context.department.parentCode
          ? `${context.department.parentName ?? context.department.parentCode} (${context.department.parentCode})`
          : "Root department"}
      </p>
      <p>
        Effective date: {context.evaluatedOn} (UTC). Changes are date-effective;
        later corrections on the same date supersede earlier revisions.
        Recording instants remain in history.
      </p>
      {!available && (
        <p role="status">
          This department is planned. Parent correction is available only once
          it is effective.
        </p>
      )}
      {error && (
        <div
          className="error"
          role="alert"
          aria-label="Department parent errors"
          tabIndex={-1}
          ref={summary}
        >
          {error} Your entries are preserved. Correct fields and retry with the
          same command; after a conflict, reload hierarchy context to discard
          the draft and use its current revision.
          <ul>
            {Object.entries(fields).map(([field, message]) => (
              <li key={field}>
                <a
                  href={`#${id}-${field}`}
                  onClick={(e) => {
                    e.preventDefault();
                    const target = document.getElementById(`${id}-${field}`);
                    if (target) target.focus();
                    else summary.current?.focus();
                  }}
                >
                  {message}
                </a>
              </li>
            ))}
          </ul>
        </div>
      )}
      <DepartmentPicker
        label="New parent department"
        id={`${id}-parentCode`}
        value={parent}
        onChange={(value) => {
          setParent(value);
          setConfirmed(false);
        }}
        valueKind="code"
        required={false}
        emptyLabel="Root department"
        disabled={busy || !available}
        error={fields.parentCode}
      />
      <label>
        Parent correction reason *
        <textarea
          id={`${id}-reason`}
          required
          maxLength={500}
          rows={3}
          value={reason}
          disabled={busy || !available}
          onChange={(e) => {
            setReason(e.target.value);
            setConfirmed(false);
          }}
          aria-invalid={!!fields.reason}
          aria-describedby={fields.reason ? `${id}-reason-error` : undefined}
        />
        {fields.reason && (
          <span id={`${id}-reason-error`} className="error">
            {fields.reason}
          </span>
        )}
      </label>
      <label className="removal-choice">
        <input
          type="checkbox"
          checked={confirmed}
          disabled={busy || !available}
          onChange={(e) => setConfirmed(e.target.checked)}
        />
        Confirm one department’s reference parent change; no employee reporting,
        position or costing change.
      </label>
      <button className="button primary" disabled={busy || !available}>
        {busy ? "Recording correction…" : "Save parent correction"}
      </button>
    </form>
  );
}
