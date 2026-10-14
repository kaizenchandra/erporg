"use client";
import { useEffect, useState } from "react";
import { api } from "@/lib/api";
import type { Employee } from "@/lib/contracts";
export type DirectoryPage = {
  items: Employee[];
  total: number;
  page: number;
  size: number;
  departments: string[];
};
export function useDirectory(query: string, revision: Employee[]) {
  const [result, setResult] = useState<{
    query: string;
    data?: DirectoryPage;
    error?: string;
  }>();
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => {
      api<DirectoryPage>(`/api/v1/directory?${query}`, {
        signal: AbortSignal.any([
          controller.signal,
          AbortSignal.timeout(10000),
        ]),
      })
        .then((data) => {
          if (!controller.signal.aborted) setResult({ query, data });
        })
        .catch((error: unknown) => {
          if (!controller.signal.aborted)
            setResult({
              query,
              error:
                error instanceof Error
                  ? error.message
                  : "Unable to load employees. Retry the search.",
            });
        });
    }, 150);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query, revision, retry]);
  const current = result?.query === query ? result : undefined;
  return {
    data: current?.data,
    error: current?.error,
    loading: !current,
    retry: () => setRetry((n) => n + 1),
  };
}
