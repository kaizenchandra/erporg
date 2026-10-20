"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import { formatDate } from "@/components/ui/primitives";
export type DepartmentEntry = {
  code: string;
  name: string;
  parentCode: string | null;
  parentName: string | null;
  costCentre: string;
  effectiveFrom: string;
  timing: "CURRENT" | "PLANNED";
  directChildren: number;
};
export type DepartmentPage = {
  items: DepartmentEntry[];
  total: number;
  page: number;
  size: number;
  evaluatedOn: string;
};
export function useDepartmentDirectory(params: string, revision: number) {
  const key = JSON.stringify([params, revision]);
  const [result, setResult] = useState<{
    key: string;
    data?: DepartmentPage;
    error?: string;
  }>();
  useEffect(() => {
    const controller = new AbortController();
    api<DepartmentPage>(`/api/v1/organisation/department-directory?${params}`, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              (error instanceof Error
                ? error.message
                : "Departments unavailable.") +
              " Retry to recheck the current catalogue.",
          });
      });
    return () => controller.abort();
  }, [key, params]);
  return result?.key === key ? result : undefined;
}
export function DepartmentDirectory({
  searchCode = "",
  onManageParent,
  focusOnLoad = false,
  onLoadedFocus,
}: {
  searchCode?: string;
  onManageParent?: (code: string) => void;
  focusOnLoad?: boolean;
  onLoadedFocus?: () => void;
}) {
  const [search, setSearch] = useState(searchCode),
    [timing, setTiming] = useState("ALL"),
    [relationship, setRelationship] = useState("ALL"),
    [parent, setParent] = useState(""),
    [direction, setDirection] = useState("asc"),
    [page, setPage] = useState(0),
    [size, setSize] = useState("8"),
    [revision, setRevision] = useState(0);
  const result = useDepartmentDirectory(
    new URLSearchParams({
      search,
      timing,
      relationship,
      parentCode: parent,
      direction,
      page: String(page),
      size,
    }).toString(),
    revision,
  );
  const heading = useRef<HTMLHeadingElement>(null),
    error = useRef<HTMLDivElement>(null),
    focus = useRef(focusOnLoad);
  useEffect(() => {
    if (result?.error) error.current?.focus();
    else if (result?.data && focus.current) {
      focus.current = false;
      heading.current?.focus();
      onLoadedFocus?.();
    }
  }, [result, onLoadedFocus]);
  const reset = () => setPage(0);
  return (
    <section aria-label="Department directory">
      <h4 ref={heading} tabIndex={-1}>
        Browse departments
      </h4>
      <div className="change-history-filters">
        <label>
          Search departments
          <input
            type="search"
            maxLength={120}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              reset();
            }}
          />
        </label>
        <label>
          Department timing
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
          Department relationship
          <select
            value={relationship}
            onChange={(e) => {
              setRelationship(e.target.value);
              setParent("");
              reset();
            }}
          >
            <option value="ALL">All departments</option>
            <option value="ROOTS">Root departments</option>
          </select>
        </label>
        <label>
          Department name order
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
          Departments per page
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
      </div>
      {parent && (
        <p>
          Direct departments of {parent} (includes planned).{" "}
          <button
            type="button"
            onClick={() => {
              setParent("");
              reset();
            }}
          >
            Clear department branch
          </button>
        </p>
      )}
      {!result ? (
        <p role="status">Loading department directory…</p>
      ) : result.error ? (
        <div ref={error} tabIndex={-1} role="alert">
          {result.error}
          <button
            type="button"
            onClick={() => {
              focus.current = true;
              setRevision((n) => n + 1);
            }}
          >
            Retry department directory
          </button>
        </div>
      ) : (
        result.data && (
          <>
            <p role="status">
              {result.data.total} department{result.data.total === 1 ? "" : "s"}{" "}
              · Page {page + 1} · Evaluated{" "}
              {formatDate(result.data.evaluatedOn)} (UTC)
            </p>
            {!result.data.items.length && (
              <p>
                No matching departments. Clear search, timing or branch filters
                to broaden the results.
              </p>
            )}
            <div className="detail-grid">
              {result.data.items.map((d) => (
                <article key={d.code}>
                  <h4>{d.name}</h4>
                  <p>
                    {d.code} · Cost centre {d.costCentre}
                  </p>
                  <p>
                    Parent:{" "}
                    {d.parentCode === null
                      ? "Root department"
                      : `${d.parentName ?? "Parent unavailable"} (${d.parentCode})`}
                  </p>
                  <p>
                    {d.timing === "PLANNED" ? "Planned" : "Effective"} ·{" "}
                    {formatDate(d.effectiveFrom)}
                  </p>
                  <p>
                    {d.directChildren} direct departments (includes planned)
                  </p>
                  {onManageParent && (
                    <button
                      type="button"
                      onClick={() => onManageParent(d.code)}
                    >
                      Manage parent for {d.code}
                    </button>
                  )}
                  {d.directChildren > 0 && (
                    <button
                      type="button"
                      onClick={() => {
                        setParent(d.code);
                        setRelationship("ALL");
                        setSearch("");
                        setTiming("ALL");
                        reset();
                        focus.current = true;
                      }}
                    >
                      View direct departments of {d.name}
                    </button>
                  )}
                </article>
              ))}
            </div>
          </>
        )
      )}
      <div className="change-history-actions">
        <button
          type="button"
          className="button secondary"
          disabled={!result?.data || page === 0}
          onClick={() => {
            focus.current = true;
            setPage((p) => p - 1);
          }}
        >
          Previous departments
        </button>
        <button
          type="button"
          className="button secondary"
          disabled={
            !result?.data || (page + 1) * Number(size) >= result.data.total
          }
          onClick={() => {
            focus.current = true;
            setPage((p) => p + 1);
          }}
        >
          Next departments
        </button>
        <button
          type="button"
          className="button secondary"
          disabled={!result}
          onClick={() => {
            reset();
            focus.current = true;
            setRevision((n) => n + 1);
          }}
        >
          Refresh department directory
        </button>
      </div>
      <p className="muted">
        Results may move between pages when departments are created. Refresh to
        restart. Reference counts include planned children; these are not
        employee headcounts.
      </p>
    </section>
  );
}
export function DepartmentParentPicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  const [search, setSearch] = useState(""),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0);
  const result = useDepartmentDirectory(
    new URLSearchParams({ search, page: String(page), size: "20" }).toString(),
    revision,
  );
  return (
    <fieldset>
      <legend>Optional parent department</legend>
      <label>
        Search parent departments
        <input
          type="search"
          value={search}
          maxLength={120}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
        />
      </label>
      <label>
        Parent department
        <select value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">Root department</option>
          {value && !result?.data?.items.some((d) => d.code === value) && (
            <option value={value}>Selected parent {value}</option>
          )}
          {result?.data?.items.map((d) => (
            <option key={d.code} value={d.code}>
              {d.name} ({d.code})
            </option>
          ))}
        </select>
      </label>
      {!result ? (
        <p role="status">Loading parent departments…</p>
      ) : result.error ? (
        <p role="alert">
          {result.error}
          <button type="button" onClick={() => setRevision((n) => n + 1)}>
            Retry parent departments
          </button>
        </p>
      ) : (
        <p role="status">
          {result.data?.total} parent candidates · Page {page + 1}. Selected
          parent is retained while searching.
        </p>
      )}
      <div className="change-history-actions">
        <button
          type="button"
          disabled={!result?.data || page === 0}
          onClick={() => setPage((p) => p - 1)}
        >
          Previous parent departments
        </button>
        <button
          type="button"
          disabled={!result?.data || (page + 1) * 20 >= result.data.total}
          onClick={() => setPage((p) => p + 1)}
        >
          Next parent departments
        </button>
      </div>
    </fieldset>
  );
}
