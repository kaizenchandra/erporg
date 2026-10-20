"use client";
import { useId, useRef, useEffect, useState } from "react";
import { formatDate } from "@/components/ui/primitives";
import { useDepartmentDirectory } from "./department-directory";
export function DepartmentPicker({
  value,
  onChange,
  label,
  id,
  error,
  disabled = false,
  required = true,
  valueKind = "name",
  emptyLabel = "Choose a configured department",
  reviewed = true,
}: {
  value: string;
  onChange: (value: string) => void;
  label: string;
  id?: string;
  error?: string;
  disabled?: boolean;
  required?: boolean;
  valueKind?: "name" | "code";
  emptyLabel?: string;
  reviewed?: boolean;
}) {
  const generated = useId(),
    selectId = id ?? generated;
  const [search, setSearch] = useState(""),
    [page, setPage] = useState(0),
    [retry, setRetry] = useState(0);
  const current = useDepartmentDirectory(
    new URLSearchParams({ search, page: String(page), size: "8" }).toString(),
    retry,
  );
  const heading = useRef<HTMLInputElement>(null),
    failure = useRef<HTMLDivElement>(null),
    refocus = useRef(false);
  useEffect(() => {
    if (current?.error) failure.current?.focus();
    else if (current?.data && refocus.current) {
      refocus.current = false;
      heading.current?.focus();
    }
  }, [current]);
  const rows = current?.data?.items ?? [];
  return (
    <fieldset className="position-picker department-picker" disabled={disabled}>
      <legend>Department selection</legend>
      <label>
        Find a department
        <input
          ref={heading}
          type="search"
          maxLength={120}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
          onKeyDown={(event) => {
            if (event.key === "Enter") event.preventDefault();
          }}
          placeholder="Name or reference code"
        />
      </label>
      <label htmlFor={selectId}>
        {label}
        {required && !label.endsWith("(required)") && <span> *</span>}
      </label>
      <select
        id={selectId}
        required={required}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={!!error}
        aria-describedby={`${selectId}-guidance${error ? ` ${selectId}-error` : ""}`}
      >
        <option value="">{emptyLabel}</option>
        {value && !rows.some((d) => d[valueKind] === value) && (
          <option value={value}>{value} · selected</option>
        )}
        {rows.map((d) => (
          <option key={d.code} value={d[valueKind]}>
            {d.name} ({d.code}) · from {formatDate(d.effectiveFrom)}
          </option>
        ))}
      </select>
      {error && (
        <p className="field-error" id={`${selectId}-error`}>
          {error}
        </p>
      )}
      {value && (
        <p className="department-selection-value">
          Selected: {rows.find((d) => d[valueKind] === value)?.name ?? value}
        </p>
      )}
      <p className="muted" id={`${selectId}-guidance`}>
        Includes planned departments.{" "}
        {required
          ? `The server checks availability on the business date when you submit${reviewed ? " and when HR approves" : ""}.`
          : "Filtering does not change employee or position records."}{" "}
        Your selection is retained while searching.
      </p>
      {!current ? (
        <p role="status">Loading department choices…</p>
      ) : current.error ? (
        <div ref={failure} tabIndex={-1} role="alert">
          {current.error}
          <button
            type="button"
            onClick={() => {
              refocus.current = true;
              setRetry((n) => n + 1);
            }}
          >
            Retry department choices
          </button>
        </div>
      ) : (
        <p role="status">
          Department choices: {current.data?.total} · Page {page + 1}
          {!rows.length &&
            ". No matching departments. Change the search to find another reference."}
        </p>
      )}
      <div className="change-history-actions">
        <button
          type="button"
          disabled={!current?.data || page === 0}
          onClick={() => {
            refocus.current = true;
            setPage((p) => p - 1);
          }}
        >
          Previous department choices
        </button>
        <button
          type="button"
          disabled={!current?.data || (page + 1) * 8 >= current.data.total}
          onClick={() => {
            refocus.current = true;
            setPage((p) => p + 1);
          }}
        >
          Next department choices
        </button>
      </div>
    </fieldset>
  );
}
