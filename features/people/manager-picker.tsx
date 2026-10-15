"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
type ManagerCandidate = { id: string; name: string; startDate: string };
import { formatDate } from "@/components/ui/primitives";
type Page = {
  items: ManagerCandidate[];
  total: number;
  page: number;
  size: number;
};
export function ManagerPicker({
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
    api<Page>(`/api/v1/reporting/managers?${query}`, {
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
              err instanceof Error ? err.message : "Unable to load managers.",
          });
      });
    return () => controller.abort();
  }, [query, key]);
  return (
    <fieldset className="manager-picker">
      <legend>Reporting manager</legend>
      <label>
        Find a manager
        <input
          maxLength={120}
          disabled={disabled}
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
          placeholder="Title or code"
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
          <option value="">Choose a configured manager</option>
          {value && !current?.data?.items.some((p) => p.id === value) && (
            <option value={value}>{value} · selected</option>
          )}
          {current?.data?.items.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name} · {p.id} · from {formatDate(p.startDate)}
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
        <p role="status">Loading manager choices…</p>
      ) : current.error ? (
        <p className="error" role="alert">
          {current.error}{" "}
          <button type="button" onClick={() => setRetry((n) => n + 1)}>
            Retry manager choices
          </button>
        </p>
      ) : (
        <>
          <p>
            {current.data?.total ?? 0} matching managers · Page {page + 1}
          </p>
          <div className="manager-pagination">
            <button
              type="button"
              className="button secondary"
              disabled={disabled || page === 0}
              onClick={() => setPage((n) => n - 1)}
            >
              Previous manager choices
            </button>
            <button
              type="button"
              className="button secondary"
              disabled={
                disabled || (page + 1) * 8 >= (current.data?.total ?? 0)
              }
              onClick={() => setPage((n) => n + 1)}
            >
              Next manager choices
            </button>
          </div>
        </>
      )}
      <p className="muted">
        HR approval changes team access on the effective date. The backend
        rejects reporting cycles, including approved future changes.
      </p>
    </fieldset>
  );
}
