"use client";
import { useState } from "react";
import { Modal } from "@/components/ui/primitives";
import type { Journal, Ledger, Line } from "./contracts";
import {
  FinancialError,
  useFinancialCommand,
  useFinancialDraft,
} from "./shared";
export function JournalEditor({
  ledger,
  journal,
  onClose,
  onSaved,
}: {
  ledger: Ledger;
  journal: Journal | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const c = useFinancialCommand(),
    [dirty, setDirty] = useState(false),
    [date, setDate] = useState(
      () => journal?.postingDate ?? new Date().toISOString().slice(0, 10),
    ),
    [reason, setReason] = useState(""),
    [lines, setLines] = useState<Line[]>(
      journal?.lines ?? [
        {
          account: "6000",
          costCentre: ledger.costCentres[0],
          debit: "",
          credit: "0",
          memo: "",
        },
        { account: "1000", costCentre: "", debit: "0", credit: "", memo: "" },
      ],
    );
  useFinancialDraft(dirty);
  const close = () => {
    if (!c.busy && (!dirty || confirm("Discard unsaved journal changes?")))
      onClose();
  };
  function change(index: number, key: keyof Line, value: string) {
    setLines((old) =>
      old.map((l, i) => (i === index ? { ...l, [key]: value } : l)),
    );
  }
  return (
    <Modal
      title={journal ? "Edit journal draft" : "Prepare manual journal"}
      onClose={close}
      closeDisabled={c.busy}
      wide
    >
      <form
        className="change-form"
        noValidate
        onChange={() => setDirty(true)}
        onSubmit={async (e) => {
          e.preventDefault();
          const saved = await c.run<Journal>("/api/v1/finance/journals", {
            id: journal?.id ?? null,
            entity: ledger.entity,
            postingDate: date,
            version: journal?.version ?? 0,
            lines,
            reason,
          });
          if (saved) {
            setDirty(false);
            onSaved();
          }
        }}
      >
        <p>
          Demonstration USD ledger · {ledger.entity}. Exactly balanced decimal
          debits and credits are validated by the server. Saving creates no
          ledger posting.
        </p>
        <FinancialError message={c.error} fields={c.fields} />
        <label>
          Journal posting date *
          <input
            type="date"
            required
            value={date}
            onChange={(e) => setDate(e.target.value)}
          />
        </label>
        {lines.map((line, i) => (
          <fieldset className="time-form-grid" key={i}>
            <legend>Journal line {i + 1}</legend>
            <label>
              Account {i + 1}
              <select
                value={line.account}
                onChange={(e) => change(i, "account", e.target.value)}
              >
                {ledger.accounts.map((a) => (
                  <option key={a.code} value={a.code}>
                    {a.code} · {a.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Cost centre {i + 1}
              <select
                value={line.costCentre}
                onChange={(e) => change(i, "costCentre", e.target.value)}
              >
                <option value="">No dimension</option>
                {ledger.costCentres.map((x) => (
                  <option key={x}>{x}</option>
                ))}
              </select>
            </label>
            <label>
              Debit {i + 1}
              <input
                inputMode="decimal"
                value={line.debit}
                onChange={(e) => change(i, "debit", e.target.value)}
              />
            </label>
            <label>
              Credit {i + 1}
              <input
                inputMode="decimal"
                value={line.credit}
                onChange={(e) => change(i, "credit", e.target.value)}
              />
            </label>
            <label>
              Line memo {i + 1}
              <input
                required
                maxLength={200}
                value={line.memo}
                onChange={(e) => change(i, "memo", e.target.value)}
              />
            </label>
            {lines.length > 2 && (
              <button
                type="button"
                onClick={() => {
                  setDirty(true);
                  setLines((old) => old.filter((_, n) => n !== i));
                }}
              >
                Remove line {i + 1}
              </button>
            )}
          </fieldset>
        ))}
        <button
          type="button"
          className="button secondary"
          disabled={lines.length >= 100 || c.busy}
          onClick={() => {
            setDirty(true);
            setLines((old) => [
              ...old,
              {
                account: ledger.accounts[0].code,
                costCentre: "",
                debit: "0",
                credit: "0",
                memo: "",
              },
            ]);
          }}
        >
          Add journal line
        </button>
        <label>
          Journal reason *
          <textarea
            required
            maxLength={500}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
          />
        </label>
        <div className="form-footer">
          <button
            type="button"
            className="button secondary"
            disabled={c.busy}
            onClick={close}
          >
            Cancel
          </button>
          <button className="button primary" disabled={c.busy}>
            Save journal draft
          </button>
        </div>
      </form>
    </Modal>
  );
}
