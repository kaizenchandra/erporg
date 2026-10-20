"use client";
import { useEffect, useRef } from "react";
export {
  useTimeRead as useFinancialRead,
  useTimeCommand as useFinancialCommand,
  useTimeDraft as useFinancialDraft,
  TimePager as FinancialPager,
} from "@/features/timekeeping/shared";
export function FinancialError({
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
    <div className="error time-error" role="alert" tabIndex={-1} ref={ref}>
      {message}
      <ul>
        {Object.entries(fields).map(([k, v]) => (
          <li key={k}>{v}</li>
        ))}
      </ul>
      <p>
        Your form values are preserved. Retry unchanged after an uncertain
        response; reload the current record after a revision conflict.
      </p>
    </div>
  ) : null;
}
export function downloadDocument(
  filename: string,
  content: string,
  type = "text/plain;charset=utf-8",
) {
  const url = URL.createObjectURL(new Blob([content], { type }));
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
