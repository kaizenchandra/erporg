"use client";
import { useEffect, useId, useRef, useState } from "react";
import { api, ApiError } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { BenefitSelections, type SelectablePlan } from "./selections";
import { BenefitHistory } from "./history";
import { Modal, formatDate } from "@/components/ui/primitives";
type Plan = {
  code: string;
  name: string;
  currency: string;
  monthlyContribution: string;
  minimumServiceDays: number;
  effectiveFrom: string;
  eligible: boolean;
  explanation: string;
  ruleVersion: string;
  mode: string;
};
type PlanPage = {
  items: Plan[];
  total: number;
  page: number;
  size: number;
  evaluatedOn: string;
};
export function BenefitsCatalogue({ actor }: { actor: Actor }) {
  const [search, setSearch] = useState("");
  const [eligibility, setEligibility] = useState("ALL");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState("8");
  const [direction, setDirection] = useState("asc");
  const [revision, setRevision] = useState(0);
  const [historyRevision, setHistoryRevision] = useState(0);
  const [selection, setSelection] = useState<SelectablePlan | null>(null);
  const [creating, setCreating] = useState(false);
  const [notice, setNotice] = useState("");
  const query = new URLSearchParams({
    search,
    eligibility,
    page: String(page),
    size,
    direction,
  }).toString();
  const key = JSON.stringify([
    query,
    revision,
    actor.subject,
    actor.tenant,
    actor.role,
  ]);
  const [result, setResult] = useState<{
    key: string;
    data?: PlanPage;
    error?: string;
  }>();
  const current = result?.key === key ? result : undefined;
  const failure = useRef<HTMLDivElement>(null);
  const heading = useRef<HTMLParagraphElement>(null);
  const focusAfterLoad = useRef(false);
  useEffect(() => {
    const controller = new AbortController();
    const options = {
      signal: AbortSignal.any([controller.signal, AbortSignal.timeout(10000)]),
    };
    api<PlanPage>(`/api/v1/benefits/plans?${query}`, options)
      .then((data) => {
        if (!controller.signal.aborted) setResult({ key, data });
      })
      .catch((err: unknown) => {
        if (!controller.signal.aborted)
          setResult({
            key,
            error:
              err instanceof Error
                ? err.message
                : "Unable to load benefit plans.",
          });
      });
    return () => controller.abort();
  }, [query, key, actor.role]);
  useEffect(() => {
    if (current?.error) failure.current?.focus();
    else if (current?.data && focusAfterLoad.current) {
      focusAfterLoad.current = false;
      heading.current?.focus();
    }
  }, [current]);
  return (
    <section className="department-reference" aria-label="Benefits catalogue">
      <div className="section-heading">
        <h3>Your benefits</h3>
        {actor.role === "HR" && (
          <button className="button primary" onClick={() => setCreating(true)}>
            Create benefit plan
          </button>
        )}
      </div>
      <p className="muted">
        Demonstration plans only. Eligibility uses your current employment
        status and configured service days. Amounts are fixed monthly employee
        contributions. Selections require independent HR approval and queue
        deductions for the later payroll module.
      </p>
      {notice && <p role="status">{notice}</p>}
      <div className="position-filters">
        <label>
          Search benefit plans
          <input
            maxLength={120}
            value={search}
            onChange={(e) => {
              setSearch(e.target.value);
              setPage(0);
            }}
          />
        </label>
        <label>
          Benefit eligibility
          <select
            value={eligibility}
            onChange={(e) => {
              setEligibility(e.target.value);
              setPage(0);
            }}
          >
            <option value="ALL">All configured plans</option>
            <option value="ELIGIBLE">Eligible for me</option>
          </select>
        </label>
        <label>
          Benefit name order
          <select
            value={direction}
            onChange={(e) => {
              setDirection(e.target.value);
              setPage(0);
            }}
          >
            <option value="asc">A to Z</option>
            <option value="desc">Z to A</option>
          </select>
        </label>
        <label>
          Benefit plans per page
          <select
            value={size}
            onChange={(event) => {
              setSize(event.target.value);
              setPage(0);
            }}
          >
            <option value="8">8</option>
            <option value="16">16</option>
            <option value="32">32</option>
          </select>
        </label>
      </div>
      {!current ? (
        <p role="status">Loading benefit plans…</p>
      ) : current.error ? (
        <div ref={failure} className="error" role="alert" tabIndex={-1}>
          {current.error}{" "}
          <button
            onClick={() => {
              focusAfterLoad.current = true;
              setRevision((n) => n + 1);
            }}
          >
            Retry benefit plans
          </button>
        </div>
      ) : (
        <>
          <p
            ref={heading}
            role="status"
            className="muted"
            tabIndex={-1}
            aria-label="Benefit plan results"
          >
            Evaluated {formatDate(current.data!.evaluatedOn)} ·{" "}
            {current.data!.total} matching{" "}
            {current.data!.total === 1 ? "plan" : "plans"} · Page {page + 1}
            {" · "}Showing{" "}
            {current.data!.items.length ? page * Number(size) + 1 : 0}–
            {current.data!.items.length
              ? Math.min(
                  current.data!.total,
                  page * Number(size) + current.data!.items.length,
                )
              : 0}
            {" of "}
            {current.data!.total}
          </p>
          {!current.data!.items.length ? (
            <p>
              No benefit plans match.{" "}
              {actor.role === "HR"
                ? "Create a demonstration plan or adjust filters."
                : "Ask HR about available plans or adjust filters."}
            </p>
          ) : (
            <div className="detail-grid">
              {current.data!.items.map((p) => (
                <article className="benefit-card" key={p.code}>
                  <h4>{p.name}</h4>
                  <p>{p.code} · Demonstration</p>
                  <p>
                    <strong>
                      {p.eligible ? "Eligible for you" : "Not eligible"}
                    </strong>{" "}
                    · {p.explanation}
                  </p>
                  <p>
                    Monthly contribution preview:{" "}
                    {new Intl.NumberFormat("en-GB", {
                      style: "currency",
                      currency: p.currency,
                    }).format(Number(p.monthlyContribution))}
                  </p>
                  <p>
                    Service requirement: {p.minimumServiceDays} days · Starts{" "}
                    {formatDate(p.effectiveFrom)}
                  </p>
                  <small>Rule {p.ruleVersion}</small>
                  {p.eligible && (
                    <button
                      className="button secondary"
                      onClick={() => setSelection(p)}
                    >
                      Select {p.name}
                    </button>
                  )}
                </article>
              ))}
            </div>
          )}
          <div className="position-pagination">
            <button
              className="button secondary"
              disabled={page === 0}
              onClick={() => {
                focusAfterLoad.current = true;
                setPage((n) => n - 1);
              }}
            >
              Previous benefit plans
            </button>
            <button
              className="button secondary"
              disabled={(page + 1) * Number(size) >= current.data!.total}
              onClick={() => {
                focusAfterLoad.current = true;
                setPage((n) => n + 1);
              }}
            >
              Next benefit plans
            </button>
            <button
              className="button secondary"
              onClick={() => {
                focusAfterLoad.current = true;
                setPage(0);
                setRevision((value) => value + 1);
              }}
            >
              Refresh benefit plans
            </button>
          </div>
        </>
      )}
      {actor.role === "HR" && (
        <BenefitHistory
          key={`benefit-history:${actor.tenant}:${actor.subject}:${actor.role}:${historyRevision}`}
        />
      )}
      <BenefitSelections
        actor={actor}
        plan={selection}
        onPlanClose={() => setSelection(null)}
      />
      {creating && (
        <CreatePlanForm
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setNotice("Demonstration benefit plan created and audit recorded.");
            setPage(0);
            setRevision((n) => n + 1);
            setHistoryRevision((n) => n + 1);
          }}
        />
      )}
    </section>
  );
}
function CreatePlanForm({
  onClose,
  onSaved,
}: {
  onClose: () => void;
  onSaved: () => void;
}) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [currency, setCurrency] = useState("GBP");
  const [monthlyContribution, setContribution] = useState("");
  const [minimumServiceDays, setServiceDays] = useState("0");
  const [effectiveFrom, setDate] = useState(
    new Date().toISOString().slice(0, 10),
  );
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [fields, setFields] = useState<Record<string, string>>({});
  const [key] = useState(() => crypto.randomUUID());
  const summary = useRef<HTMLDivElement>(null);
  const formId = useId();
  const dirty =
    !!(code || name || monthlyContribution || reason) ||
    minimumServiceDays !== "0" ||
    currency !== "GBP" ||
    effectiveFrom !== new Date().toISOString().slice(0, 10);
  useEffect(() => {
    if (error) summary.current?.focus();
  }, [error]);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent) => {
      if (dirty) e.preventDefault();
    };
    window.addEventListener("beforeunload", handler);
    return () => window.removeEventListener("beforeunload", handler);
  }, [dirty]);
  const close = () => {
    if (
      !busy &&
      (!dirty || window.confirm("Discard this unsaved benefit plan?"))
    )
      onClose();
  };
  const props = (field: string) => ({
    id: `${formId}-${field}`,
    "aria-invalid": !!fields[field],
    "aria-describedby": fields[field] ? `${formId}-${field}-error` : undefined,
  });
  const inline = (field: string) =>
    fields[field] ? (
      <span className="error" id={`${formId}-${field}-error`}>
        {fields[field]}
      </span>
    ) : null;
  return (
    <Modal title="Create demonstration benefit plan" onClose={close}>
      <form
        className="change-form"
        noValidate
        onSubmit={async (e) => {
          e.preventDefault();
          if (busy) return;
          setBusy(true);
          setError("");
          setFields({});
          try {
            await api("/api/v1/benefits/plans", {
              method: "POST",
              body: JSON.stringify({
                code,
                name,
                currency,
                monthlyContribution,
                minimumServiceDays,
                effectiveFrom: effectiveFrom || null,
                reason,
                idempotencyKey: key,
              }),
            });
            onSaved();
          } catch (err) {
            setError(
              err instanceof Error ? err.message : "Unable to create plan.",
            );
            setFields(err instanceof ApiError ? err.fieldErrors : {});
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>
          Creates an immutable demonstration reference. Active or On leave
          employees meeting the service/start rule are eligible for preview. No
          enrolment, dependants or payroll transfer occurs.
        </p>
        {error && (
          <div
            className="error"
            role="alert"
            aria-label="Benefit plan errors"
            tabIndex={-1}
            ref={summary}
          >
            {error} Your entries are preserved. Correct the fields and retry.
            <ul>
              {Object.entries(fields).map(([field, message]) => (
                <li key={field}>
                  <a
                    href={`#${formId}-${field}`}
                    onClick={(e) => {
                      e.preventDefault();
                      document.getElementById(`${formId}-${field}`)?.focus();
                    }}
                  >
                    {message}
                  </a>
                </li>
              ))}
            </ul>
          </div>
        )}
        <label>
          Benefit plan code <span>*</span>
          <input
            required
            maxLength={30}
            {...props("code")}
            value={code}
            onChange={(e) => setCode(e.target.value)}
          />
          {inline("code")}
        </label>
        <label>
          Benefit plan name <span>*</span>
          <input
            required
            maxLength={120}
            {...props("name")}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          {inline("name")}
        </label>
        <label>
          Contribution currency <span>*</span>
          <select
            {...props("currency")}
            value={currency}
            onChange={(e) => setCurrency(e.target.value)}
          >
            <option>GBP</option>
            <option>USD</option>
            <option>EUR</option>
          </select>
          {inline("currency")}
        </label>
        <label>
          Monthly employee contribution <span>*</span>
          <input
            required
            inputMode="decimal"
            maxLength={12}
            {...props("monthlyContribution")}
            value={monthlyContribution}
            onChange={(e) => setContribution(e.target.value)}
            placeholder="25.50"
          />
          {inline("monthlyContribution")}
        </label>
        <label>
          Minimum service days <span>*</span>
          <input
            required
            inputMode="numeric"
            maxLength={4}
            {...props("minimumServiceDays")}
            value={minimumServiceDays}
            onChange={(e) => setServiceDays(e.target.value)}
          />
          {inline("minimumServiceDays")}
        </label>
        <label>
          Benefit plan start date <span>*</span>
          <input
            required
            type="date"
            {...props("effectiveFrom")}
            value={effectiveFrom}
            onChange={(e) => setDate(e.target.value)}
          />
          {inline("effectiveFrom")}
        </label>
        <label>
          Benefit configuration reason <span>*</span>
          <textarea
            required
            maxLength={500}
            rows={3}
            {...props("reason")}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
          {inline("reason")}
        </label>
        <div className="form-footer">
          <button
            className="button secondary"
            type="button"
            disabled={busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={busy}>
            {busy ? "Creating…" : "Save benefit plan"}
          </button>
        </div>
      </form>
    </Modal>
  );
}
