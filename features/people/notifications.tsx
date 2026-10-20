"use client";
import { useEffect, useRef, useState } from "react";
import { Icon } from "@/components/ui/icon";
import { Empty, Modal } from "@/components/ui/primitives";
import { api } from "@/lib/api";
import type { Notification } from "@/lib/contracts";

export function NotificationPanel({ onClose }: { onClose: () => void }) {
  const [attempt, setAttempt] = useState(0);
  const [result, setResult] = useState<{
    items?: Notification[];
    error?: string;
  }>({});
  const errorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const controller = new AbortController();
    const signal = AbortSignal.any([
      controller.signal,
      AbortSignal.timeout(10000),
    ]);
    api<Notification[]>("/api/v1/notifications", { signal })
      .then((items) => {
        if (!controller.signal.aborted) setResult({ items });
      })
      .catch((error: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            error:
              error instanceof Error
                ? error.message
                : "Unable to load notifications. Retry to get the latest messages.",
          });
      });
    return () => controller.abort();
  }, [attempt]);
  useEffect(() => {
    if (result.error) errorRef.current?.focus();
  }, [result.error]);
  const reload = () => {
    setResult({});
    setAttempt((value) => value + 1);
  };
  return (
    <Modal title="Notifications" onClose={onClose}>
      <div className="notification-list">
        <p className="muted">
          Latest 50 messages. Availability here does not mean they have been
          read or acknowledged. Open the relevant workspace for current details.
        </p>
        {result.error ? (
          <div
            role="alert"
            aria-label="Notification errors"
            tabIndex={-1}
            ref={errorRef}
          >
            <p>{result.error}</p>
            <button className="button secondary" onClick={reload}>
              Retry notifications
            </button>
          </div>
        ) : result.items ? (
          <>
            <button className="button secondary" onClick={reload}>
              Refresh notifications
            </button>
            {result.items.length ? (
              result.items.map((item) => (
                <article key={item.id}>
                  <Icon name="check" />
                  <div>
                    <strong>{item.message}</strong>
                    <small>
                      <time dateTime={item.recordedAt}>
                        {new Date(item.recordedAt).toLocaleString()}
                      </time>
                    </small>
                  </div>
                </article>
              ))
            ) : (
              <Empty title="A quiet moment">
                Status updates on your requests will appear here.
              </Empty>
            )}
          </>
        ) : (
          <p role="status">Loading notifications…</p>
        )}
      </div>
    </Modal>
  );
}
