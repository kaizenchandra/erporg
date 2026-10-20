"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { ReferenceHistory } from "./reference-history";
import { DepartmentPicker } from "./department-picker";
import { Modal, formatDate } from "@/components/ui/primitives";
export type Position = {
  code: string;
  title: string;
  departmentCode: string;
  effectiveFrom: string;
};
export type PositionDirectoryPage = {
  items: (Position & {
    departmentName: string;
    timing: "CURRENT" | "PLANNED";
  })[];
  total: number;
  page: number;
  size: number;
  evaluatedOn: string;
};
export function PositionCatalogue({ actor }: { actor: Actor }) {
  const [loaded, setLoaded] = useState<{
    key: string;
    data?: PositionDirectoryPage;
    error?: string;
  }>();
  const [search, setSearch] = useState("");
  const [department, setDepartment] = useState("");
  const [page, setPage] = useState(0);
  const [direction, setDirection] = useState("asc");
  const [timing, setTiming] = useState("ALL");
  const [size, setSize] = useState("8");
  const [creationRevision, setCreationRevision] = useState(0);
  const heading = useRef<HTMLHeadingElement>(null);
  const errorSummary = useRef<HTMLDivElement>(null);
  const focusAfterLoad = useRef(false);
  const [revision, setRevision] = useState(0);
  const [creating, setCreating] = useState(false);

  const [notice, setNotice] = useState("");
  const query = new URLSearchParams({
    search,
    departmentCode: department,
    page: String(page),
    size,
    timing,
    direction,
  }).toString();
  const queryKey = JSON.stringify([
    query,
    revision,
    actor.subject,
    actor.tenant,
    actor.role,
    actor.employeeId,
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
    api<PositionDirectoryPage>(
      `/api/v1/organisation/position-directory?${query}`,
      options,
    )
      .then((rows) => {
        if (!controller.signal.aborted)
          setLoaded({ key: queryKey, data: rows });
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
  useEffect(() => {
    if (current?.error) {
      focusAfterLoad.current = false;
      errorSummary.current?.focus();
    } else if (current?.data && focusAfterLoad.current) {
      focusAfterLoad.current = false;
      heading.current?.focus();
    }
  }, [current]);
  const reset = () => {
    focusAfterLoad.current = false;
    setPage(0);
  };
  const refresh = () => {
    focusAfterLoad.current = true;
    setPage(0);
    setRevision((n) => n + 1);
  };
  return (
    <section className="department-reference" aria-label="Position catalogue">
      <div className="section-heading">
        <h3 ref={heading} tabIndex={-1}>
          Position catalogue
        </h3>
        {actor.role === "HR" && (
          <button className="button primary" onClick={() => setCreating(true)}>
            Create position
          </button>
        )}
      </div>
      <p className="muted">
        Positions define roles within departments. Creating a position does not
        assign an employee or change reporting lines. Search department
        references by name or code; choices are paginated. Current and planned
        labels use the server’s UTC evaluation date. These are reference
        positions, not vacancies or employee counts.
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
              reset();
            }}
            placeholder="Title or code"
          />
        </label>
        <DepartmentPicker
          label="Position department"
          value={department}
          onChange={(value) => {
            setDepartment(value);
            reset();
          }}
          valueKind="code"
          required={false}
          emptyLabel="All departments"
        />
        <label>
          Position title order
          <select
            value={direction}
            onChange={(e) => {
              setDirection(e.target.value);
              reset();
            }}
          >
            <option value="asc">A to Z</option>
            <option value="desc">Z to A</option>
          </select>
        </label>
        <label>
          Position timing
          <select
            value={timing}
            onChange={(e) => {
              setTiming(e.target.value);
              reset();
            }}
          >
            <option value="ALL">Current and planned</option>
            <option value="CURRENT">Current</option>
            <option value="PLANNED">Planned</option>
          </select>
        </label>
        <label>
          Positions per page
          <select
            value={size}
            onChange={(e) => {
              setSize(e.target.value);
              reset();
            }}
          >
            <option value="8">8</option>
            <option value="20">20</option>
            <option value="50">50</option>
          </select>
        </label>
        <button type="button" className="button secondary" onClick={refresh}>
          Refresh positions
        </button>
      </div>
      {loading ? (
        <p role="status">Loading positions…</p>
      ) : error ? (
        <div className="error" role="alert" ref={errorSummary} tabIndex={-1}>
          {error} <button onClick={refresh}>Retry positions</button>
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
                    {p.code} · {p.departmentName} ({p.departmentCode})
                  </p>
                  <p>
                    {p.timing === "PLANNED" ? "Planned" : "Effective"} ·{" "}
                    {formatDate(p.effectiveFrom)}
                  </p>
                </article>
              ))}
            </div>
          )}
          <div className="position-pagination">
            <span role="status">
              {result?.items.length ? page * Number(size) + 1 : 0}–
              {result?.items.length
                ? page * Number(size) + result.items.length
                : 0}{" "}
              of {result?.total ?? 0} matching positions · Page {page + 1} of{" "}
              {Math.max(1, Math.ceil((result?.total ?? 0) / Number(size)))} ·
              Evaluated {result?.evaluatedOn} (UTC)
            </span>
            <button
              className="button secondary"
              disabled={page === 0}
              onClick={() => {
                focusAfterLoad.current = true;
                setPage((n) => n - 1);
              }}
            >
              Previous positions
            </button>
            <button
              className="button secondary"
              disabled={(page + 1) * Number(size) >= (result?.total ?? 0)}
              onClick={() => {
                focusAfterLoad.current = true;
                setPage((n) => n + 1);
              }}
            >
              Next positions
            </button>
          </div>
        </>
      )}
      {actor.role === "HR" && (
        <ReferenceHistory
          key={`position-history:${actor.tenant}:${actor.subject}:${actor.role}:${creationRevision}`}
          kind="POSITION"
        />
      )}
      {creating && (
        <CreatePositionForm
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setNotice("Position created and audit recorded.");
            setCreationRevision((n) => n + 1);
            refresh();
          }}
        />
      )}
    </section>
  );
}
function CreatePositionForm({
  onClose,
  onSaved,
}: {
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
        <DepartmentPicker
          label="Department for position"
          reviewed={false}
          id={`${formId}-departmentCode`}
          value={departmentCode}
          onChange={setDepartment}
          error={fieldErrors.departmentCode}
          valueKind="code"
          disabled={busy}
        />
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
