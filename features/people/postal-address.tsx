"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import { Modal } from "@/components/ui/primitives";
export type PostalAddress = {
  line1: string;
  line2: string;
  locality: string;
  region: string;
  postalCode: string;
  countryCode: string;
};
type Context = {
  employeeId: string;
  employeeVersion: number;
  legacyAddress: string | null;
  address: PostalAddress | null;
  countries: { code: string; name: string }[];
};
const empty: PostalAddress = {
  line1: "",
  line2: "",
  locality: "",
  region: "",
  postalCode: "",
  countryCode: "",
};
export function PostalAddressPanel({
  employeeId,
  onRecorded,
}: {
  employeeId: string;
  onRecorded: () => Promise<void>;
}) {
  const path = `/api/v1/employees/${encodeURIComponent(employeeId)}/postal-address`;
  const [revision, setRevision] = useState(0),
    [editing, setEditing] = useState(false);
  const [result, setResult] = useState<{
    key: string;
    data?: Context;
    error?: string;
  }>();
  const key = `${path}:${revision}`,
    current = result?.key === key ? result : undefined;
  const button = useRef<HTMLButtonElement>(null),
    focus = useRef(false);
  useEffect(() => {
    const c = new AbortController();
    api<Context>(path, {
      signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
    })
      .then((data) => {
        if (!c.signal.aborted) setResult({ key, data });
      })
      .catch((e) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error ? e.message : "Postal address unavailable.",
          });
      });
    return () => c.abort();
  }, [key, path]);
  useEffect(() => {
    if (current?.data && focus.current) {
      focus.current = false;
      button.current?.focus();
    }
  }, [current]);
  const close = () => {
    focus.current = true;
    setEditing(false);
    setRevision((n) => n + 1);
  };
  return (
    <section
      className="department-reference"
      aria-label="Structured postal address"
    >
      <div className="section-heading">
        <h3>Postal address</h3>
        <span>Private · employee & HR</span>
      </div>
      <p>
        Structured entry uses the same home-address approval history.
        Country/region choices do not validate postal delivery or statutory
        residency.
      </p>
      {!current ? (
        <p role="status">Loading postal address…</p>
      ) : current.error ? (
        <>
          <p role="alert">{current.error}</p>
          <button
            className="button secondary"
            onClick={() => setRevision((n) => n + 1)}
          >
            Retry postal address
          </button>
        </>
      ) : (
        current.data && (
          <>
            {current.data.address ? (
              <address>
                {Object.values(current.data.address).filter(Boolean).join(", ")}
              </address>
            ) : (
              <p>
                {current.data.legacyAddress
                  ? `Existing free-text address: ${current.data.legacyAddress}`
                  : "No home address recorded."}
              </p>
            )}
            <button
              ref={button}
              className="button secondary"
              onClick={() => setEditing(true)}
            >
              Request structured address
            </button>
            <p className="muted">
              Track, withdraw, or review requests in Approvals → Employee
              changes. Existing free-text entries are preserved without inferred
              components.
            </p>
            {editing && (
              <PostalEditor
                data={current.data}
                path={path}
                onClose={close}
                onSaved={async () => {
                  await onRecorded();
                  close();
                }}
              />
            )}
          </>
        )
      )}
    </section>
  );
}
function PostalEditor({
  data,
  path,
  onClose,
  onSaved,
}: {
  data: Context;
  path: string;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const initial = data.address ?? empty,
    today = new Date().toISOString().slice(0, 10);
  const [address, setAddress] = useState<PostalAddress>({ ...initial }),
    [date, setDate] = useState(today),
    [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [errors, setErrors] = useState<Record<string, string>>({});
  const summary = useRef<HTMLParagraphElement>(null),
    attempt = useRef<{ fingerprint: string; key: string } | null>(null),
    id = useId();
  const dirty =
    JSON.stringify(address) !== JSON.stringify(initial) ||
    date !== today ||
    reason !== "";
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
  const fieldError = (field: string) =>
    errors[field] ? (
      <span className="error" id={`${id}-${field}`}>
        {errors[field]}
      </span>
    ) : null;
  return (
    <Modal
      title="Request structured address"
      closeDisabled={busy}
      onClose={() => {
        if (
          !busy &&
          (!dirty || window.confirm("Discard unsaved postal address?"))
        )
          onClose();
      }}
    >
      <form
        className="change-form"
        aria-label="Postal address request"
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setErrors({});
          const body = {
              address,
              effectiveDate: date,
              reason,
              version: data.employeeVersion,
            },
            fingerprint = JSON.stringify(body);
          if (attempt.current?.fingerprint !== fingerprint)
            attempt.current = { fingerprint, key: crypto.randomUUID() };
          try {
            await api(path, {
              method: "POST",
              body: JSON.stringify({
                ...body,
                idempotencyKey: attempt.current.key,
              }),
            });
            await onSaved();
          } catch (e) {
            if (e instanceof ApiError) setErrors(e.fieldErrors);
            setError(
              (e instanceof Error ? e.message : "Unable to submit") +
                " Entries are preserved. Retry unchanged values to recover a submitted request, or close and refresh Approvals.",
            );
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          Replace the home address for {data.employeeId} after independent HR
          approval, effective on the requested date. Prior values remain in
          private history.
        </p>
        {error && (
          <p
            className="error"
            role="alert"
            aria-label="Postal address request errors"
            ref={summary}
            tabIndex={-1}
          >
            {error}
          </p>
        )}
        <fieldset disabled={busy}>
          <legend>Requested postal address</legend>
          {(
            [
              ["line1", "Address line 1", 120, true],
              ["line2", "Address line 2", 120, false],
              ["locality", "City / locality", 80, true],
              ["region", "State / region", 80, false],
              ["postalCode", "Postal code", 30, false],
            ] as const
          ).map(([field, label, max, required]) => (
            <label key={field}>
              {label}
              {required ? " (required)" : " (optional)"}
              <input
                value={address[field]}
                maxLength={max}
                required={required}
                aria-invalid={!!errors[field]}
                aria-describedby={errors[field] ? `${id}-${field}` : undefined}
                onChange={(e) =>
                  setAddress((a) => ({ ...a, [field]: e.target.value }))
                }
              />
              {fieldError(field)}
            </label>
          ))}
          <label>
            Country / region (required)
            <select
              required
              value={address.countryCode}
              aria-invalid={!!errors.countryCode}
              aria-describedby={
                errors.countryCode ? `${id}-countryCode` : undefined
              }
              onChange={(e) =>
                setAddress((a) => ({ ...a, countryCode: e.target.value }))
              }
            >
              <option value="">Choose country / region</option>
              {data.countries.map((c) => (
                <option key={c.code} value={c.code}>
                  {c.name} ({c.code})
                </option>
              ))}
            </select>
            {fieldError("countryCode")}
          </label>
          <label>
            Address effective date (required)
            <input
              required
              type="date"
              value={date}
              aria-invalid={!!errors.effectiveDate}
              aria-describedby={
                errors.effectiveDate ? `${id}-effectiveDate` : undefined
              }
              onChange={(e) => setDate(e.target.value)}
            />
            {fieldError("effectiveDate")}
          </label>
          <label>
            Address change reason (required)
            <textarea
              required
              maxLength={500}
              value={reason}
              aria-invalid={!!errors.reason}
              aria-describedby={errors.reason ? `${id}-reason` : undefined}
              onChange={(e) => setReason(e.target.value)}
            />
            {fieldError("reason")}
          </label>
        </fieldset>
        <button className="button primary" disabled={busy}>
          {busy ? "Submitting…" : "Submit postal address for approval"}
        </button>
      </form>
    </Modal>
  );
}
