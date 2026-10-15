"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import type { Department } from "./department-reference";
import { Modal, formatDate } from "@/components/ui/primitives";
export type Position = {
  code: string;
  title: string;
  departmentCode: string;
  effectiveFrom: string;
};
type PositionPage = {
  items: Position[];
  total: number;
  page: number;
  size: number;
};
type PositionAudit = {
  code: string;
  actor: string;
  reason: string;
  recordedAt: string;
  effectiveFrom: string;
  correlationId: string;
};
export function PositionCatalogue({ actor }: { actor: Actor }) {
  const [loaded, setLoaded] = useState<{
    key: string;
    data?: PositionPage;
    error?: string;
  }>();
  const [departments, setDepartments] = useState<Department[]>([]);
  const [history, setHistory] = useState<PositionAudit[]>([]);
  const [search, setSearch] = useState("");
  const [department, setDepartment] = useState("");
  const [page, setPage] = useState(0);
  const [direction, setDirection] = useState("asc");
  const [revision, setRevision] = useState(0);
  const [creating, setCreating] = useState(false);

  const [notice, setNotice] = useState("");
  const query = new URLSearchParams({
    search,
    departmentCode: department,
    page: String(page),
    size: "8",
    direction,
  }).toString();
  const queryKey = JSON.stringify([
    query,
    revision,
    actor.subject,
    actor.tenant,
    actor.role,
  ]);
  const current = loaded?.key === queryKey ? loaded : undefined;
  const result = current?.data;
  const loading = !current;
  const error = current?.error ?? "";
  useEffect(() => {
    const controller = new AbortController();
    const options = {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    };
    Promise.all([
      api<PositionPage>(`/api/v1/organisation/positions?${query}`, options),
      api<Department[]>("/api/v1/organisation/departments", options),
      actor.role === "HR"
        ? api<PositionAudit[]>(
            "/api/v1/organisation/positions/history",
            options,
          )
        : Promise.resolve([]),
    ])
      .then(([rows, refs, audit]) => {
        if (!controller.signal.aborted) {
          setLoaded({ key: queryKey, data: rows });
          setDepartments(refs);
          setHistory(audit);
        }
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setLoaded({
            key: queryKey,
            error:
              err instanceof Error ? err.message : "Unable to load positions.",
          });
      });
    return () => controller.abort();
  }, [query, queryKey, actor.role]);
  return (
    <section className="department-reference" aria-label="Position catalogue">
      <div className="section-heading">
        <h3>Position catalogue</h3>
        {actor.role === "HR" && (
          <button className="button primary" onClick={() => setCreating(true)}>
            Create position
          </button>
        )}
      </div>
      <p className="muted">
        Positions define roles within departments. Creating a position does not
        assign an employee or change reporting lines. Department choices show up
        to 1,000 references.
      </p>
      {notice && <p role="status">{notice}</p>}
      <div className="position-filters">
        <label>
          Search positions
          <input
            maxLength={120}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
            placeholder="Title or code"
          />
        </label>
        <label>
          Position department
          <select
            value={department}
            onChange={(e) => {
              setDepartment(e.target.value);
              setPage(0);
            }}
          >
            <option value="">All departments</option>
            {departments.map((d) => (
              <option key={d.code} value={d.code}>
                {d.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          Position title order
          <select
            value={direction}
            onChange={(e) => {
              setDirection(e.target.value);
              setPage(0);
            }}
          >
            <option value="asc">A to Z</option>
            <option value="desc">Z to A</option>
          </select>
        </label>
      </div>
      {loading ? (
        <p role="status">Loading positions…</p>
      ) : error ? (
        <div className="error" role="alert">
          {error}{" "}
          <button onClick={() => setRevision((n) => n + 1)}>
            Retry positions
          </button>
        </div>
      ) : (
        <>
          {!result?.items.length ? (
            <p>
              No positions match these filters.{" "}
              {actor.role === "HR"
                ? "Create a position or adjust your search."
                : "Ask HR to configure a position, or adjust your search."}
            </p>
          ) : (
            <div className="detail-grid">
              {result.items.map((p) => (
                <article className="position-card" key={p.code}>
                  <h4>{p.title}</h4>
                  <p>
                    {p.code} ·{" "}
                    {departments.find((d) => d.code === p.departmentCode)
                      ?.name ?? p.departmentCode}
                  </p>
                  <p>
                    {p.effectiveFrom > new Date().toISOString().slice(0, 10)
                      ? "Planned"
                      : "Effective"}{" "}
                    · {formatDate(p.effectiveFrom)}
                  </p>
                </article>
              ))}
            </div>
          )}
          <div className="position-pagination">
            <span>
              {result?.total ?? 0} matching positions · Page {page + 1}
            </span>
            <button
              className="button secondary"
              disabled={page === 0}
              onClick={() => setPage((n) => n - 1)}
            >
              Previous positions
            </button>
            <button
              className="button secondary"
              disabled={(page + 1) * 8 >= (result?.total ?? 0)}
              onClick={() => setPage((n) => n + 1)}
            >
              Next positions
            </button>
          </div>
        </>
      )}
      {actor.role === "HR" && (
        <details>
          <summary>Position configuration history (latest 100)</summary>
          {history.length ? (
            history.map((h) => (
              <article className="history-event" key={h.code}>
                <div>
                  <strong>{h.code} · Created</strong>
                  <p>{h.reason}</p>
                  <small>
                    {h.actor} · {new Date(h.recordedAt).toISOString()} ·
                    Effective {formatDate(h.effectiveFrom)}
                  </small>
                </div>
              </article>
            ))
          ) : (
            <p>No position changes recorded.</p>
          )}
        </details>
      )}
      {creating && (
        <CreatePositionForm
          departments={departments}
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setNotice("Position created and audit recorded.");
            setPage(0);
            setRevision((n) => n + 1);
          }}
        />
      )}
    </section>
  );
}
function CreatePositionForm({
  departments,
  onClose,
  onSaved,
}: {
  departments: Department[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [code, setCode] = useState("");
  const [title, setTitle] = useState("");
  const [departmentCode, setDepartment] = useState("");
  const [effectiveFrom, setDate] = useState(
    new Date().toISOString().slice(0, 10),
  );
  const [reason, setReason] = useState("");
  const [key] = useState(() => crypto.randomUUID());
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const summary = useRef<HTMLDivElement>(null);
  const formId = useId();
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const fieldProps = (name: string) => ({
    id: `${formId}-${name}`,
    "aria-invalid": !!fieldErrors[name],
    "aria-describedby": fieldErrors[name]
      ? `${formId}-${name}-error`
      : undefined,
  });
  const fieldError = (name: string) =>
    fieldErrors[name] ? (
      <span className="error" id={`${formId}-${name}-error`}>
        {fieldErrors[name]}
      </span>
    ) : null;
  const dirty =
    !!(code || title || departmentCode || reason) ||
    effectiveFrom !== new Date().toISOString().slice(0, 10);
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
    if (!busy && (!dirty || window.confirm("Discard this unsaved position?")))
      onClose();
  };
  return (
    <Modal title="Create position" onClose={close}>
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);

          setFieldErrors({});
          try {
            await api("/api/v1/organisation/positions", {
              method: "POST",
              body: JSON.stringify({
                code,
                title,
                departmentCode,
                effectiveFrom: effectiveFrom || null,
                reason,
                idempotencyKey: key,
              }),
            });
            onSaved();
          } catch (err) {
            setFieldErrors(err instanceof ApiError ? err.fieldErrors : {});
            setError(
              err instanceof Error ? err.message : "Unable to create position.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          The position code, department and title are fixed after creation. This
          creates a reference only; no employee is assigned.
        </p>
        {error && (
          <div
            className="error"
            role="alert"
            aria-label="Position errors"
            tabIndex={-1}
            ref={summary}
          >
            {error} Your entries are preserved. Correct the fields and retry.
            {!!Object.keys(fieldErrors).length && (
              <ul>
                {Object.entries(fieldErrors).map(([name, message]) => (
                  <li key={name}>
                    <a
                      href={`#${formId}-${name}`}
                      onClick={(event) => {
                        event.preventDefault();
                        document.getElementById(`${formId}-${name}`)?.focus();
                      }}
                    >
                      {message}
                    </a>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
        <label>
          Position code <span>*</span>
          <input
            required
            maxLength={30}
            {...fieldProps("code")}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder="DESIGN-LEAD"
          />
          {fieldError("code")}
        </label>
        <label>
          Position title <span>*</span>
          <input
            required
            maxLength={120}
            {...fieldProps("title")}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
          />
          {fieldError("title")}
        </label>
        <label>
          Department for position <span>*</span>
          <select
            required
            {...fieldProps("departmentCode")}
            value={departmentCode}
            onChange={(e) => setDepartment(e.target.value)}
          >
            <option value="">Choose a department</option>
            {departments.map((d) => (
              <option key={d.code} value={d.code}>
                {d.name} · from {formatDate(d.effectiveFrom)}
              </option>
            ))}
          </select>
          {fieldError("departmentCode")}
        </label>
        <label>
          Position start date <span>*</span>
          <input
            required
            type="date"
            min={new Date().toISOString().slice(0, 10)}
            {...fieldProps("effectiveFrom")}
            value={effectiveFrom}
            onChange={(e) => setDate(e.target.value)}
          />
          {fieldError("effectiveFrom")}
        </label>
        <label>
          Position configuration reason <span>*</span>
          <textarea
            required
            maxLength={500}
            rows={3}
            {...fieldProps("reason")}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {fieldError("reason")}
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            onClick={close}
            disabled={busy}
          >
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "Creating…" : "Save position"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
