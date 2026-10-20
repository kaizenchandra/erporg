"use client";
import { useEffect, useRef, useState } from "react";
import { api } from "@/lib/api";
import type { Actor } from "@/lib/contracts";
import { formatDate } from "@/components/ui/primitives";
import type { LegalEntityPage, LegalEntityOptions } from "./contracts";
import { ReferenceRetirement } from "./reference-retirement";
import { CreateLegalEntity } from "./create-legal-entity";
export function LegalEntitiesSettings({ actor }: { actor: Actor }) {
  const [search, setSearch] = useState(""),
    [draft, setDraft] = useState(""),
    [status, setStatus] = useState("ALL"),
    [page, setPage] = useState(0),
    [revision, setRevision] = useState(0),
    [creating, setCreating] = useState(false),
    [notice, setNotice] = useState("");
  const [managing, setManaging] = useState<string | null>(null);
  const retirementFocus = useRef<string | null>(null),
    heading = useRef<HTMLHeadingElement>(null);
  const [result, setResult] = useState<{
    key: string;
    data?: LegalEntityPage;
    options?: LegalEntityOptions;
    error?: string;
  }>();
  const summary = useRef<HTMLParagraphElement>(null),
    button = useRef<HTMLButtonElement>(null),
    focus = useRef(false);
  const key = JSON.stringify([
    actor.subject,
    actor.tenant,
    search,
    status,
    page,
    revision,
  ]);
  const current = result?.key === key ? result : undefined;
  const data = current?.data;
  useEffect(() => {
    if (actor.role !== "HR") return;
    const c = new AbortController(),
      options = {
        signal: AbortSignal.any([c.signal, AbortSignal.timeout(10000)]),
      };
    const q = new URLSearchParams({
      search,
      status,
      page: String(page),
      size: "10",
    });
    Promise.all([
      api<LegalEntityPage>(`/api/v1/legal-entities?${q}`, options),
      api<LegalEntityOptions>("/api/v1/legal-entities/options", options),
    ])
      .then(([data, options]) => {
        if (!c.signal.aborted) setResult({ key, data, options });
      })
      .catch((e) => {
        if (!c.signal.aborted)
          setResult({
            key,
            error:
              e instanceof Error
                ? e.message
                : "Legal-entity catalogue unavailable.",
          });
      });
    return () => c.abort();
  }, [actor.role, key, search, status, page]);
  useEffect(() => {
    if (current?.error) summary.current?.focus();
    if (current?.data && focus.current) {
      focus.current = false;
      if (retirementFocus.current) {
        const target = document.getElementById(
          `reference-retirement-${retirementFocus.current}`,
        );
        if (target) target.focus();
        else heading.current?.focus();
        retirementFocus.current = null;
      } else button.current?.focus();
    }
  }, [current]);
  if (actor.role !== "HR")
    return (
      <section className="department-reference">
        <h2>Organisation settings</h2>
        <p role="alert">
          HR permission is required for legal-entity reference settings.
        </p>
      </section>
    );
  const close = () => {
    focus.current = true;
    setCreating(false);
    setRevision((n) => n + 1);
  };
  return (
    <section
      className="department-reference legal-entity-settings"
      aria-label="Legal-entity references"
    >
      <div className="section-heading">
        <h2 ref={heading} tabIndex={-1}>
          Legal-entity references
        </h2>
        <span>HR configuration</span>
      </div>
      <p>
        Declared, unverified company references. HR can propose employee
        assignments for independent review. Assignment does not verify
        registration or activate contract, statutory, tax or payroll rules.
      </p>
      {notice && (
        <p role="status" className="success">
          {notice}
        </p>
      )}
      <form
        className="inbox-search"
        role="search"
        aria-label="Search legal-entity references"
        onSubmit={(e) => {
          e.preventDefault();
          setSearch(draft);
          setPage(0);
        }}
      >
        <label>
          Entity code or name
          <input
            value={draft}
            maxLength={120}
            onChange={(e) => setDraft(e.target.value)}
          />
        </label>
        <button className="button secondary">Search references</button>
      </form>
      <label>
        Reference availability
        <select
          value={status}
          onChange={(e) => {
            setStatus(e.target.value);
            setPage(0);
          }}
        >
          <option value="ALL">All references</option>
          <option value="CURRENT">Available by today (UTC)</option>
          <option value="PLANNED">Planned</option>
          <option value="RETIRED">Retired</option>
        </select>
      </label>
      {!current ? (
        <p role="status">Loading legal-entity references…</p>
      ) : current.error ? (
        <>
          <p
            role="alert"
            aria-label="Legal-entity catalogue errors"
            tabIndex={-1}
            ref={summary}
          >
            {current.error}
          </p>
          <button
            className="button secondary"
            onClick={() => setRevision((n) => n + 1)}
          >
            Retry legal-entity references
          </button>
        </>
      ) : (
        data && (
          <>
            <button
              ref={button}
              className="button primary"
              onClick={() => setCreating(true)}
            >
              Create legal-entity reference
            </button>
            {!data.total && (
              <p className="quiet-note">
                {search || status !== "ALL"
                  ? "No references match these filters. Change the search or availability filter."
                  : "No legal-entity references recorded. HR can record a declared reference without assigning employees."}
              </p>
            )}
            <div className="detail-grid">
              {data.items.map((entry) => (
                <article
                  className="benefit-card"
                  key={entry.entity.code}
                  aria-label={`Legal entity ${entry.entity.code}`}
                >
                  <h3>{entry.entity.name}</h3>
                  <p>{entry.entity.code} · Unverified reference</p>
                  <dl>
                    <dt>Country / region</dt>
                    <dd>{entry.entity.countryCode}</dd>
                    <dt>Reference currency</dt>
                    <dd>{entry.entity.currencyCode}</dd>
                    <dt>Reference time zone</dt>
                    <dd>{entry.entity.timeZone}</dd>
                    <dt>Available from (UTC calendar)</dt>
                    <dd>{formatDate(entry.entity.effectiveFrom)}</dd>
                  </dl>
                  {data.retirementDates[entry.entity.code] && (
                    <p>
                      {data.retirementDates[entry.entity.code] >
                      new Date().toISOString().slice(0, 10)
                        ? "Retirement scheduled"
                        : "Retired"}{" "}
                      · {formatDate(data.retirementDates[entry.entity.code])}
                    </p>
                  )}
                  <button
                    id={`reference-retirement-${entry.entity.code}`}
                    className="button secondary"
                    onClick={() => setManaging(entry.entity.code)}
                  >
                    Manage retirement · {entry.entity.code}
                  </button>
                  <details>
                    <summary>Creation audit</summary>
                    <p>
                      Recorded by {entry.createdBy} ·{" "}
                      {new Date(entry.recordedAt).toLocaleString()}
                    </p>
                    <p>{entry.reason}</p>
                    <p>Correlation: {entry.correlationId}</p>
                    <p>
                      Initial creation snapshot; no previous value or later
                      amendment.
                    </p>
                  </details>
                </article>
              ))}
            </div>
            <p className="muted">
              {data.total} {data.total === 1 ? "reference" : "references"} ·
              page {page + 1} · ordered by code. No bulk action applies.
            </p>
            <div className="form-footer">
              <button
                className="button secondary"
                disabled={!page}
                onClick={() => setPage((p) => p - 1)}
              >
                Previous references
              </button>
              <button
                className="button secondary"
                disabled={(page + 1) * 10 >= data.total}
                onClick={() => setPage((p) => p + 1)}
              >
                Next references
              </button>
            </div>
          </>
        )
      )}
      {managing && (
        <ReferenceRetirement
          code={managing}
          actor={actor}
          onChanged={() => setRevision((n) => n + 1)}
          onClose={() => {
            retirementFocus.current = managing;
            focus.current = true;
            setManaging(null);
            setRevision((n) => n + 1);
          }}
        />
      )}
      {creating && current?.options && (
        <CreateLegalEntity
          options={current.options}
          onClose={close}
          onSaved={(entry) => {
            setNotice(
              `${entry.entity.code} recorded as an unverified reference. No employees were assigned.`,
            );
            setSearch("");
            setDraft("");
            setStatus("ALL");
            setPage(0);
            close();
          }}
        />
      )}
    </section>
  );
}
