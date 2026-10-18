"use client";
import { useState } from "react";
import type { Actor, PeopleSummary } from "@/lib/contracts";
import { Approvals } from "./approvals";
import { ContactInbox } from "./contact-inbox";
export function ApprovalWorkspace(props: {
  revision: PeopleSummary;
  pending: number;
  actor: Actor;
  onSaved: (message?: string) => void;
}) {
  const [category, setCategory] = useState("changes");
  return (
    <>
      <nav className="people-tabs" aria-label="Approval categories">
        <button
          className={category === "changes" ? "active" : ""}
          aria-pressed={category === "changes"}
          onClick={() => setCategory("changes")}
        >
          Employee changes
        </button>
        <button
          className={category === "contacts" ? "active" : ""}
          aria-pressed={category === "contacts"}
          onClick={() => setCategory("contacts")}
        >
          Emergency contacts
        </button>
      </nav>
      <p className="muted">
        The navigation badge counts employee changes. Contact queue totals are
        shown within Emergency contacts.
      </p>
      {category === "changes" ? (
        <Approvals {...props} />
      ) : (
        <ContactInbox actor={props.actor} onSaved={props.onSaved} />
      )}
    </>
  );
}
