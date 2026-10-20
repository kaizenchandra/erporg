"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { LegalEntityEntry } from "@/features/settings/contracts";
import { formatDate } from "@/components/ui/primitives";
type Page = {
  items: LegalEntityEntry[];
  total: number;
  page: number;
  size: number;
};
export function EntityPicker({
  value,
  onChange,
  disabled,
  id,
  error,
}: {
  value: string;
  onChange: (value: string) => void;
  disabled: boolean;
  id: string;
  error?: string;
}) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [retry, setRetry] = useState(0);
  const query = new URLSearchParams({
    search,
    page: String(page),
    size: "8",
  }).toString();
  const key = JSON.stringify([query, retry]);
  const [result, setResult] = useState<{
    key: string;
    data?: Page;
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  useEffect(() => {
    const controller = new AbortController();
    api<Page>(`/api/v1/legal-entities?${query}`, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              err instanceof Error ? err.message : "Unable to load entities.",
          });
      });
    return () => controller.abort();
  }, [query, key]);
  return (
    <fieldset className="entity-picker">
      <legend>Declared employing entity</legend>
      <label>
        Find a declared entity
        <input
          maxLength={120}
          disabled={disabled}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
          placeholder="Name or code"
        />
      </label>
      <label>
        New value {!disabled && <span>*</span>}
        <select
          id={id}
          disabled={disabled}
          required={!disabled}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          aria-invalid={!!error}
          aria-describedby={error ? `${id}-error` : undefined}
        >
          <option value="">Choose a declared entity</option>
          {value &&
            !current?.data?.items.some((p) => p.entity.code === value) && (
              <option value={value}>{value} · selected</option>
            )}
          {current?.data?.items.map((p) => (
            <option key={p.entity.code} value={p.entity.code}>
              {p.entity.name} · {p.entity.code} · {p.entity.timeZone} · from{" "}
              {formatDate(p.entity.effectiveFrom)}
            </option>
          ))}
        </select>
        {error && (
          <span className="error" id={`${id}-error`}>
            {error}
          </span>
        )}
      </label>
      {!current ? (
        <p role="status">Loading entity choices…</p>
      ) : current.error ? (
        <p className="error" role="alert">
          {current.error}{" "}
          <button type="button" onClick={() => setRetry((n) => n + 1)}>
            Retry entity choices
          </button>
        </p>
      ) : (
        <>
          <p>
            {current.data?.total ?? 0} matching entities · Page {page + 1}
          </p>
          <div className="calendar-pagination">
            <button
              type="button"
              className="button secondary"
              disabled={disabled || page === 0}
              onClick={() => setPage((n) => n - 1)}
            >
              Previous entity choices
            </button>
            <button
              type="button"
              className="button secondary"
              disabled={
                disabled || (page + 1) * 8 >= (current.data?.total ?? 0)
              }
              onClick={() => setPage((n) => n + 1)}
            >
              Next entity choices
            </button>
          </div>
        </>
      )}
      <p className="muted">
        Assignment must not precede the reference effective date. Pending or
        approved retirement blocks assignment. Metadata is supplied and
        unverified; no payroll, tax, payment or statutory rule is activated.
      </p>
    </fieldset>
  );
}
