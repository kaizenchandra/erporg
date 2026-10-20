"use client";
import { useState } from "react";
import type { Actor, PeopleSummary } from "@/lib/contracts";
import { Approvals } from "./approvals";
import { RetirementInbox } from "@/features/settings/retirement-inbox";
import { ContactInbox } from "./contact-inbox";
import { BenefitSelectionInbox } from "@/features/benefits/selection-inbox";
import { TimeWorkspace } from "@/features/timekeeping/workspace";
export function ApprovalWorkspace(props: {
  revision: PeopleSummary;
  pending: number;
  actor: Actor;
  onSaved: (message?: string) => void;
}) {
  const [category, setCategory] = useState("changes");
  const choose = (next: string) => {
    if (
      window.dispatchEvent(
        new Event("atlas-before-navigation", { cancelable: true }),
      )
    )
      setCategory(next);
  };
  return (
    <>
      <nav className="people-tabs" aria-label="Approval categories">
        <button
          className={category === "changes" ? "active" : ""}
          aria-pressed={category === "changes"}
          onClick={() => choose("changes")}
        >
          Employee changes
        </button>
        <button
          className={category === "contacts" ? "active" : ""}
          aria-pressed={category === "contacts"}
          onClick={() => choose("contacts")}
        >
          Emergency contacts
        </button>
        {props.actor.role === "HR" && (
          <button
            className={category === "retirements" ? "active" : ""}
            aria-pressed={category === "retirements"}
            onClick={() => choose("retirements")}
          >
            Reference retirements
          </button>
        )}
        <button
          className={category === "benefits" ? "active" : ""}
          aria-pressed={category === "benefits"}
          onClick={() => choose("benefits")}
        >
          Benefit selections
        </button>
        <button
          className={category === "time" ? "active" : ""}
          aria-pressed={category === "time"}
          onClick={() => choose("time")}
        >
          Timesheets
        </button>
      </nav>
      <p className="muted">
        The navigation badge counts employee changes. Each other category shows
        its own scoped request total.
      </p>
      {category === "changes" ? (
        <Approvals {...props} />
      ) : category === "time" ? (
        <TimeWorkspace actor={props.actor} inbox />
      ) : category === "retirements" ? (
        <RetirementInbox actor={props.actor} />
      ) : category === "benefits" ? (
        <BenefitSelectionInbox actor={props.actor} onSaved={props.onSaved} />
      ) : (
        <ContactInbox actor={props.actor} onSaved={props.onSaved} />
      )}
    </>
  );
}
