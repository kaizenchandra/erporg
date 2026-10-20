"use client";
import { useEffect, useId, useRef, useState } from "react";
import { Modal } from "@/components/ui/primitives";
import { api, ApiError } from "@/lib/api";
import type {
  LegalEntity,
  LegalEntityEntry,
  LegalEntityOptions,
} from "./contracts";
export function CreateLegalEntity({
  options,
  onClose,
  onSaved,
}: {
  options: LegalEntityOptions;
  onClose: () => void;
  onSaved: (entry: LegalEntityEntry) => void;
}) {
  const [today] = useState(() => new Date().toISOString().slice(0, 10));
  const initial: LegalEntity = {
    code: "",
    name: "",
    countryCode: "",
    currencyCode: "",
    timeZone: "",
    effectiveFrom: today,
  };
  const [entity, setEntity] = useState(initial),
    [reason, setReason] = useState(""),
    [error, setError] = useState(""),
    [errors, setErrors] = useState<Record<string, string>>({}),
    [busy, setBusy] = useState(false);
  const attempt = useRef<{ fingerprint: string; key: string } | null>(null),
    summary = useRef<HTMLParagraphElement>(null),
    id = useId();
  const dirty =
    reason !== "" || JSON.stringify(entity) !== JSON.stringify(initial);
  useEffect(() => {
    const f = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", f);
    return () => window.removeEventListener("beforeunload", f);
  }, [dirty]);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  const invalid = (field: string) => ({
    "aria-invalid": !!errors[field],
    "aria-describedby": errors[field] ? `${id}-${field}` : undefined,
  });
  const fieldError = (field: string) =>
    errors[field] ? (
      <span className="error" id={`${id}-${field}`}>
        {errors[field]}
      </span>
    ) : null;
  return (
    <Modal
      title="Create legal-entity reference"
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          (!dirty || window.confirm("Discard unsaved legal-entity reference?"))
        )
          onClose();
      }}
    >
      <form
        className="change-form legal-entity-form"
        aria-label="Legal-entity reference form"
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setErrors({});
          const body = { entity, reason },
            fingerprint = JSON.stringify(body);
          if (attempt.current?.fingerprint !== fingerprint)
            attempt.current = { fingerprint, key: crypto.randomUUID() };
          try {
            const receipt = await api<LegalEntityEntry>(
              "/api/v1/legal-entities",
              {
                method: "POST",
                body: JSON.stringify({
                  ...body,
                  idempotencyKey: attempt.current.key,
                }),
              },
            );
            onSaved(receipt);
          } catch (e) {
            if (e instanceof ApiError) setErrors(e.fieldErrors);
            setError(
              (e instanceof Error ? e.message : "Unable to record reference") +
                " Entries are preserved. Retry unchanged values to recover a recorded reference, or close and refresh the catalogue.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          Record an immutable, unverified reference. This does not register a
          company, assign employees, approve a payment currency or activate
          country/payroll rules. Amendments and retirement require a later
          workflow.
        </p>
        {error && (
          <p
            role="alert"
            aria-label="Legal-entity reference errors"
            className="error"
            tabIndex={-1}
            ref={summary}
          >
            {error}
          </p>
        )}
        <fieldset disabled={busy}>
          <legend>Declared reference details</legend>
          {(
            [
              ["code", "Entity code", 30],
              ["name", "Declared entity name", 120],
            ] as const
          ).map(([field, label, max]) => (
            <label key={field}>
              {label} (required)
              <input
                required
                maxLength={max}
                value={entity[field]}
                {...invalid(field)}
                onChange={(e) =>
                  setEntity((v) => ({ ...v, [field]: e.target.value }))
                }
              />
              {fieldError(field)}
            </label>
          ))}
          <label>
            Country / region (required)
            <select
              required
              value={entity.countryCode}
              {...invalid("countryCode")}
              onChange={(e) =>
                setEntity((v) => ({ ...v, countryCode: e.target.value }))
              }
            >
              <option value="">Choose country / region</option>
              {options.countries.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name} ({c.code})
                </option>
              ))}
            </select>
            {fieldError("countryCode")}
          </label>
          <label>
            Reference currency (required)
            <select
              required
              value={entity.currencyCode}
              {...invalid("currencyCode")}
              onChange={(e) =>
                setEntity((v) => ({ ...v, currencyCode: e.target.value }))
              }
            >
              <option value="">Choose reference currency</option>
              {options.currencies.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.code} · {c.name}
                </option>
              ))}
            </select>
            {fieldError("currencyCode")}
          </label>
          <p className="muted">
            Currency options can include obsolete or non-tender codes; inclusion
            does not establish payment eligibility or compatibility with the
            declared country.
          </p>
          <label>
            Reference time zone (required)
            <select
              required
              value={entity.timeZone}
              {...invalid("timeZone")}
              onChange={(e) =>
                setEntity((v) => ({ ...v, timeZone: e.target.value }))
              }
            >
              <option value="">Choose reference time zone</option>
              {options.timeZones.map((z) => (
                <option key={z} value={z}>
                  {z}
                </option>
              ))}
            </select>
            {fieldError("timeZone")}
          </label>
          <label>
            Reference effective date (required)
            <input
              required
              type="date"
              value={entity.effectiveFrom}
              {...invalid("effectiveFrom")}
              onChange={(e) =>
                setEntity((v) => ({ ...v, effectiveFrom: e.target.value }))
              }
            />
            {fieldError("effectiveFrom")}
          </label>
          <label>
            Configuration reason (required)
            <textarea
              required
              maxLength={500}
              value={reason}
              {...invalid("reason")}
              onChange={(e) => setReason(e.target.value)}
            />
            {fieldError("reason")}
          </label>
        </fieldset>
        <p>
          The creation snapshot and audit are retained. Effective dates use the
          current UTC application calendar; this reference does not change
          employee date boundaries.
        </p>
        <button className="button primary" disabled={busy}>
          {busy ? "Recording…" : "Record legal-entity reference"}
        </button>
      </form>
    </Modal>
  );
}
