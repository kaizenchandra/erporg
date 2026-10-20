"use client";
import type { Context, Onboarding, Requisition } from "./contracts";
import {
  TalentForm,
  Field,
  Notes,
  WorkerField,
  value,
  Decision,
  type Saved,
  useTalentRead,
} from "./shared";
export function RequisitionEditor({
  context,
  onSaved,
}: {
  context: Context;
  onSaved: Saved;
}) {
  return (
    <TalentForm
      url="/api/v1/talent/requisitions"
      onSaved={onSaved}
      label="Save requisition"
      build={(f) => ({
        title: value(f, "title"),
        department: value(f, "department"),
        location: value(f, "location"),
        managerId: value(f, "managerId"),
        start: value(f, "start"),
        reason: value(f, "reason"),
      })}
    >
      <Field name="title" label="Job title" />
      <Field name="department" label="Department" />
      <Field name="location" label="Work location" />
      <WorkerField
        workers={context.workers.items}
        name="managerId"
        label="Hiring manager"
      />
      <Field name="start" label="Employment start date" type="date" />
      <p>
        Department and manager eligibility are revalidated by Core HR before a
        hire can be approved. Requisitions require independent approval before
        candidates can be entered.
      </p>
    </TalentForm>
  );
}
export function RecruitmentDetail({
  r,
  onSaved,
}: {
  r: Requisition;
  onSaved: Saved;
}) {
  const status = r.meta.status;
  return (
    <>
      <p>
        {r.department} · {r.location} · starts {r.start} · manager {r.managerId}
      </p>
      <Decision
        kind="requisitions"
        record={r}
        onSaved={onSaved}
        actions={
          status === "DRAFT" || status === "RETURNED"
            ? ["SUBMIT"]
            : status === "SUBMITTED"
              ? ["APPROVE", "RETURN"]
              : status === "OPEN"
                ? ["CLOSE"]
                : []
        }
      />
      <h4>Candidate pipeline</h4>
      {!r.candidates.length && (
        <p>
          No candidates yet. Open the approved requisition to add candidates.
        </p>
      )}
      {r.candidates.map((c) => (
        <article className="talent-subcard" key={c.id}>
          <h4>
            {c.name} · {c.stage}
          </h4>
          <p>
            {c.email} · {c.phone}
          </p>
          {c.interviewAt && (
            <p>
              Interview {c.interviewAt} · {c.interviewNotes}
            </p>
          )}
          {c.offer && <p>Offer: {c.offer}</p>}
          {c.employeeId && (
            <p>
              Created Core HR employee: {c.employeeId}. A joining plan is
              available in Onboarding.
            </p>
          )}
          {status === "OPEN" && !["HIRED", "REJECTED"].includes(c.stage) && (
            <TalentForm
              key={`${c.id}-${r.meta.version}`}
              url={`/api/v1/talent/requisitions/${r.meta.id}/candidates`}
              onSaved={onSaved}
              label={`Update ${c.name}`}
              confirm="Apply the selected candidate action? Hire submission starts the existing independently approved Core HR process; approval creates one employee and joining plan."
              build={(f) => ({
                version: r.meta.version,
                candidateId: c.id,
                action: value(f, "action"),
                interviewAt: value(f, "interviewAt"),
                notes: value(f, "notes"),
                offer: value(f, "offer"),
                reason: value(f, "reason"),
              })}
            >
              <label>
                Candidate action *
                <select name="action">
                  {(c.stage === "APPLIED"
                    ? ["INTERVIEW", "REJECTED"]
                    : c.stage === "INTERVIEW"
                      ? ["OFFER", "REJECTED"]
                      : c.stage === "OFFER"
                        ? ["ACCEPTED", "REJECTED"]
                        : c.stage === "ACCEPTED"
                          ? ["SEND_HIRE"]
                          : ["APPROVE_HIRE", "REJECT_HIRE"]
                  ).map((a) => (
                    <option key={a}>{a}</option>
                  ))}
                </select>
              </label>
              {c.stage === "APPLIED" && (
                <>
                  <Field
                    name="interviewAt"
                    label="Interview date/time with offset (ISO 8601)"
                    required={false}
                  />
                  <Notes name="notes" label="Interview plan" required={false} />
                </>
              )}
              {c.stage === "INTERVIEW" && (
                <Notes name="offer" label="Offer terms" required={false} />
              )}
            </TalentForm>
          )}
        </article>
      ))}
      {status === "OPEN" && (
        <details>
          <summary>Add a candidate</summary>
          <TalentForm
            url={`/api/v1/talent/requisitions/${r.meta.id}/candidates`}
            onSaved={onSaved}
            label="Add candidate"
            build={(f) => ({
              version: r.meta.version,
              action: "ADD",
              name: value(f, "name"),
              email: value(f, "email"),
              phone: value(f, "phone"),
              reason: value(f, "reason"),
            })}
          >
            <Field name="name" label="Candidate name" />
            <Field name="email" label="Candidate email" type="email" />
            <Field name="phone" label="Candidate phone" type="tel" />
          </TalentForm>
        </details>
      )}
    </>
  );
}
export function JoiningDetail({
  r,
  context,
  onSaved,
}: {
  r: Onboarding;
  context: Context;
  onSaved: Saved;
}) {
  const worker = useTalentRead<Context>(
    `/api/v1/talent/context?search=${encodeURIComponent(r.employeeId)}`,
    r.meta.version,
  );
  const w = worker?.data?.workers.items.find((w) => w.id === r.employeeId);
  return (
    <>
      <p>
        Core HR hire {r.hireId} · employee {r.employeeId}
      </p>
      <p>Joining form: {r.joiningForm || "Not completed"}</p>
      {r.meta.status === "OPEN" && (
        <>
          <details>
            <summary>Complete joining form</summary>
            <TalentForm
              url={`/api/v1/talent/onboarding/${r.meta.id}/actions`}
              onSaved={onSaved}
              label="Save joining form"
              build={(f) => ({
                version: r.meta.version,
                action: "FORM",
                joiningForm: value(f, "joiningForm"),
                reason: value(f, "reason"),
              })}
            >
              <Notes
                name="joiningForm"
                label="Joining information and acknowledgements"
                value={r.joiningForm}
              />
              <p>
                Use permitted joining information only; do not enter banking,
                tax identifiers or credentials.
              </p>
            </TalentForm>
          </details>
          {context.actor.role === "HR" && (
            <details>
              <summary>Add joining task</summary>
              <TalentForm
                url={`/api/v1/talent/onboarding/${r.meta.id}/actions`}
                onSaved={onSaved}
                label="Add task"
                build={(f) => ({
                  version: r.meta.version,
                  action: "ADD_TASKS",
                  tasks: [
                    {
                      title: value(f, "title"),
                      assignee: value(f, "assignee"),
                      dependsOn: f.getAll("dependsOn").map(String),
                    },
                  ],
                  reason: value(f, "reason"),
                })}
              >
                <Field name="title" label="Task title" />
                <WorkerField
                  workers={context.workers.items}
                  name="assignee"
                  label="Task assignee"
                />
                {r.tasks.length > 0 && (
                  <fieldset>
                    <legend>Prerequisites (selected tasks only)</legend>
                    {r.tasks.map((t) => (
                      <label key={t.id}>
                        <input type="checkbox" name="dependsOn" value={t.id} />
                        {t.title}
                      </label>
                    ))}
                  </fieldset>
                )}
              </TalentForm>
            </details>
          )}
        </>
      )}
      {r.tasks.map((t) => (
        <article className="talent-subcard" key={t.id}>
          <h4>
            {t.title} · {t.complete ? "Complete" : "Pending"}
          </h4>
          <p>
            Assigned to {t.assignee}. {t.dependsOn.length} prerequisites.
          </p>
          {t.evidence && (
            <p>
              {t.evidence} · {t.completedAt}
            </p>
          )}
          {!t.complete &&
            t.assignee === context.actor.employeeId &&
            r.meta.status === "OPEN" && (
              <TalentForm
                url={`/api/v1/talent/onboarding/${r.meta.id}/actions`}
                onSaved={onSaved}
                label={`Complete ${t.title}`}
                build={(f) => ({
                  version: r.meta.version,
                  action: "COMPLETE_TASK",
                  taskId: t.id,
                  evidence: value(f, "evidence"),
                  reason: value(f, "reason"),
                })}
              >
                <Notes name="evidence" label="Completion evidence" />
              </TalentForm>
            )}
        </article>
      ))}
      {context.actor.role === "HR" && r.meta.status === "OPEN" && (
        <TalentForm
          url={`/api/v1/talent/onboarding/${r.meta.id}/actions`}
          onSaved={onSaved}
          label="Complete onboarding and activate employment"
          disabled={!w}
          confirm="Activate this one employee through Core HR? All joining tasks and the joining form must be complete; this does not provision sign-in access."
          build={(f) => ({
            version: r.meta.version,
            employeeVersion: w?.version,
            action: "ACTIVATE",
            reason: value(f, "reason"),
          })}
        >
          <p>
            Employee revision{" "}
            {w?.version ?? "unavailable — refresh the workspace"}. Activation is
            permitted on or after the employment start date and does not create
            credentials.
          </p>
        </TalentForm>
      )}
    </>
  );
}
