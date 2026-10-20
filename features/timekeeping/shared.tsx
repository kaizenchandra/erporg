"use client";
import { useEffect, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
export function useTimeRead<T>(url: string, revision: number) {
  const key = JSON.stringify([url, revision]);
  const [result, setResult] = useState<{
    key: string;
    data?: T;
    error?: string;
  }>();
  useEffect(() => {
    const controller = new AbortController();
    api<T>(url, {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((e: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error: e instanceof Error ? e.message : "Time records unavailable.",
          });
      });
    return () => controller.abort();
  }, [url, key]);
  return result?.key === key ? result : undefined;
}
export function useTimeCommand() {
  const [busy, setBusy] = useState(false),
    [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const pending = useRef(false),
    receipt = useRef<{ input: string; key: string } | null>(null);
  async function run<T>(
    url: string,
    body: Record<string, unknown>,
  ): Promise<T | undefined> {
    if (pending.current) return;
    pending.current = true;
    setBusy(true);
    setError("");
    setFields({});
    const input = JSON.stringify([url, body]);
    if (receipt.current?.input !== input)
      receipt.current = { input, key: crypto.randomUUID() };
    try {
      const result = await api<T>(url, {
        method: "POST",
        body: JSON.stringify({ ...body, idempotencyKey: receipt.current.key }),
      });
      receipt.current = null;
      return result;
    } catch (e: unknown) {
      setError(
        e instanceof Error ? e.message : "Unable to confirm the outcome.",
      );
      setFields(e instanceof ApiError ? e.fieldErrors : {});
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }
  return { busy, error, fields, run };
}
export function TimeError({
  message,
  fields = {},
}: {
  message?: string;
  fields?: Record<string, string>;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (message) ref.current?.focus();
  }, [message]);
  return message ? (
    <div
      className="error time-error"
      role="alert"
      aria-label="Time workflow errors"
      tabIndex={-1}
      ref={ref}
    >
      {message} Your entries are preserved. Retry unchanged after an uncertain
      response; reload the current record after a revision conflict.
      <ul>
        {Object.entries(fields).map(([k, v]) => (
          <li key={k}>{v}</li>
        ))}
      </ul>
    </div>
  ) : null;
}
export function useTimeDraft(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return;
    const leave = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    const navigate = (e: Event) => {
      if (!window.confirm("Discard unsaved time entries?")) e.preventDefault();
    };
    window.addEventListener("beforeunload", leave);
    window.addEventListener("atlas-before-navigation", navigate);
    return () => {
      window.removeEventListener("beforeunload", leave);
      window.removeEventListener("atlas-before-navigation", navigate);
    };
  }, [dirty]);
}
export function TimePager({
  page,
  total,
  onPage,
}: {
  page: number;
  total: number;
  onPage: (n: number) => void;
}) {
  return (
    <div className="time-pager">
      <span role="status">
        {total} records · Page {page + 1} of{" "}
        {Math.max(1, Math.ceil(total / 10))}
      </span>
      <button
        type="button"
        disabled={page === 0}
        onClick={() => onPage(page - 1)}
      >
        Previous page
      </button>
      <button
        type="button"
        disabled={(page + 1) * 10 >= total}
        onClick={() => onPage(page + 1)}
      >
        Next page
      </button>
    </div>
  );
}
