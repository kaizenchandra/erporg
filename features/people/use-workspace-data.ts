"use client";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError, clearSession } from "@/lib/api";
import type {
  Actor,
  PeopleSummary,
  Employee,
  Notification,
} from "@/lib/contracts";
export function useWorkspaceData() {
  const [actor, setActor] = useState<Actor | null>(null);
  const [employees, setEmployees] = useState<Employee[]>([]);
  const [summary, setSummary] = useState<PeopleSummary>({
    total: 0,
    active: 0,
    onLeave: 0,
    pending: 0,
  });
  const [self, setSelf] = useState<Employee | null>(null);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const pending = useRef<AbortController | null>(null);
  const reset = useCallback(() => {
    pending.current?.abort();
    setActor(null);
    setEmployees([]);
    setSelf(null);
    setSummary({ total: 0, active: 0, onLeave: 0, pending: 0 });
    setNotifications([]);
    setLoading(false);
    clearSession();
  }, []);
  const refresh = useCallback(async () => {
    pending.current?.abort();
    const controller = new AbortController();
    pending.current = controller;
    const signal = AbortSignal.any([
      controller.signal,
      AbortSignal.timeout(10000),
    ]);
    try {
      const identity = await api<Actor>("/api/v1/session", { signal });
      const [people, alerts, counts, own] = await Promise.all([
        api<{ items: Employee[] }>("/api/v1/directory?size=100", { signal }),
        api<Notification[]>("/api/v1/notifications", { signal }),
        api<PeopleSummary>("/api/v1/people-summary", { signal }),
        api<Employee>(
          `/api/v1/employees/${encodeURIComponent(identity.employeeId)}`,
          { signal },
        ),
      ]);
      if (controller.signal.aborted) return;
      setActor(identity);
      setEmployees(people.items);
      setSummary(counts);
      setSelf(own);
      setNotifications(alerts);
      setError("");
    } catch (err) {
      if (controller.signal.aborted) return;
      if (err instanceof ApiError && err.status === 401) reset();
      else
        setError(
          err instanceof Error
            ? err.message
            : "Connection failed. Please retry.",
        );
    } finally {
      if (!controller.signal.aborted) setLoading(false);
    }
  }, [reset]);
  useEffect(() => {
    // Only awaited remote responses update state; this is external data synchronization.
    // eslint-disable-next-line react-hooks/set-state-in-effect
    refresh();
    const expired = () => reset();
    window.addEventListener("atlas-session-expired", expired);
    return () => {
      pending.current?.abort();
      window.removeEventListener("atlas-session-expired", expired);
    };
  }, [refresh, reset]);
  return {
    actor,
    summary,
    self,
    employees,
    notifications,
    loading,
    error,
    setError,
    refresh,
    reset,
  };
}
