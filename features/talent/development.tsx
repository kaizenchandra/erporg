"use client";
import type { Context, Course, Enrollment, Review } from "./contracts";
import {
  TalentForm,
  Field,
  Notes,
  WorkerField,
  value,
  type Saved,
} from "./shared";
export function ReviewEditor({
  context,
  onSaved,
}: {
  context: Context;
  onSaved: Saved;
}) {
  return (
    <TalentForm
      url="/api/v1/talent/reviews"
      onSaved={onSaved}
      label="Open review cycle"
      build={(f) => ({
        employeeId: value(f, "employeeId"),
        cycle: value(f, "cycle"),
        from: value(f, "from"),
        through: value(f, "through"),
        minimumRating: Number(value(f, "minimumRating")),
        maximumRating: Number(value(f, "maximumRating")),
        reason: value(f, "reason"),
      })}
    >
      <WorkerField workers={context.workers.items} />
      <Field name="cycle" label="Review cycle" />
      <Field name="from" label="Review from" type="date" />
      <Field name="through" label="Review through" type="date" />
      <Field
        name="minimumRating"
        label="Minimum rating"
        type="number"
        value="1"
      />
      <Field
        name="maximumRating"
        label="Maximum rating"
        type="number"
        value="5"
      />
      <p>
        Only HR or the worker’s current direct manager can open and assess a
        cycle. Workers submit their own self review.
      </p>
    </TalentForm>
  );
}
export function ReviewDetail({
  r,
  context,
  onSaved,
}: {
  r: Review;
  context: Context;
  onSaved: Saved;
}) {
  const editable = ["OPEN", "RETURNED"].includes(r.meta.status);
  const url = `/api/v1/talent/reviews/${r.meta.id}/actions`;
  return (
    <>
      <p>
        {r.from}–{r.through} · ratings {r.minimumRating}–{r.maximumRating}
      </p>
      {r.decisionComment && <p>Review decision: {r.decisionComment}</p>}
      {r.rating !== null && <p>Final rating: {r.rating}</p>}
      {r.selfReview && <p>Self review: {r.selfReview}</p>}
      <h4>Goals and feedback</h4>
      {!r.goals.length && <p>No goals have been set.</p>}
      {r.goals.map((g) => (
        <article className="talent-subcard" key={g.id}>
          <h4>{g.text}</h4>
          <p>{g.feedback || "No feedback recorded."}</p>
          {editable && (
            <TalentForm
              url={url}
              onSaved={onSaved}
              label="Record feedback"
              build={(f) => ({
                version: r.meta.version,
                action: "FEEDBACK",
                goalId: g.id,
                feedback: value(f, "feedback"),
                reason: value(f, "reason"),
              })}
            >
              <Notes name="feedback" label="Feedback" value={g.feedback} />
            </TalentForm>
          )}
        </article>
      ))}
      {editable && (
        <>
          <TalentForm
            url={url}
            onSaved={onSaved}
            label="Add goal"
            build={(f) => ({
              version: r.meta.version,
              action: "GOAL",
              goal: value(f, "goal"),
              reason: value(f, "reason"),
            })}
          >
            <Notes name="goal" label="Goal" />
          </TalentForm>
          {context.actor.employeeId === r.employeeId && (
            <TalentForm
              url={url}
              onSaved={onSaved}
              label="Submit self review"
              build={(f) => ({
                version: r.meta.version,
                action: "SUBMIT",
                selfReview: value(f, "selfReview"),
                reason: value(f, "reason"),
              })}
            >
              <Notes
                name="selfReview"
                label="Self review"
                value={r.selfReview}
              />
            </TalentForm>
          )}
        </>
      )}
      {r.meta.status === "SUBMITTED" &&
        context.actor.employeeId !== r.employeeId &&
        context.actor.role !== "EMPLOYEE" && (
          <TalentForm
            url={url}
            onSaved={onSaved}
            label="Record review decision"
            confirm="Finalize or return this one review? A completed review is retained and cannot be edited."
            build={(f) => ({
              version: r.meta.version,
              action: value(f, "action"),
              rating:
                value(f, "rating") === "" ? null : Number(value(f, "rating")),
              reason: value(f, "reason"),
            })}
          >
            <label>
              Review decision *
              <select name="action">
                <option>APPROVE</option>
                <option>RETURN</option>
              </select>
            </label>
            <Field
              name="rating"
              label="Final rating (required for approval)"
              type="number"
              required={false}
            />
          </TalentForm>
        )}
    </>
  );
}
export function CourseEditor({ onSaved }: { onSaved: Saved }) {
  return (
    <TalentForm
      url="/api/v1/talent/courses"
      onSaved={onSaved}
      label="Publish course"
      build={(f) => ({
        title: value(f, "title"),
        description: value(f, "description"),
        role: value(f, "role"),
        validDays: Number(value(f, "validDays")),
        reason: value(f, "reason"),
      })}
    >
      <Field name="title" label="Course title" />
      <Notes
        name="description"
        label="Course guidance and completion expectations"
      />
      <label>
        Eligible assignment role *
        <select name="role">
          <option>ALL</option>
          <option>EMPLOYEE</option>
          <option>MANAGER</option>
          <option>HR</option>
        </select>
      </label>
      <Field
        name="validDays"
        label="Validity in days (0 means no expiration)"
        type="number"
        value="365"
      />
      <p>
        Published course configuration is retained. Completion uses evidence
        entered by the assigned worker; no external LMS certificate is implied.
      </p>
    </TalentForm>
  );
}
export function CourseDetail({
  r,
  context,
  onSaved,
}: {
  r: Course;
  context: Context;
  onSaved: Saved;
}) {
  return (
    <>
      <p>{r.description}</p>
      <p>
        Eligible role: {r.role}. Completion validity:{" "}
        {r.validDays === 0 ? "No expiration" : `${r.validDays} days`}.
      </p>
      <TalentForm
        url="/api/v1/talent/learning"
        onSaved={() => onSaved()}
        label="Assign course"
        build={(f) => ({
          courseId: r.meta.id,
          employeeId: value(f, "employeeId"),
          due: value(f, "due"),
          reason: value(f, "reason"),
        })}
      >
        <WorkerField
          workers={context.workers.items}
          selected={context.actor.employeeId}
        />
        <Field name="due" label="Completion due date" type="date" />
        <p>
          Self-enrolment and authorised direct-report assignments are checked
          against the configured role. View the result in Assigned learning.
        </p>
      </TalentForm>
    </>
  );
}
export function LearningDetail({
  r,
  context,
  onSaved,
}: {
  r: Enrollment;
  context: Context;
  onSaved: Saved;
}) {
  const expired =
    r.expires !== null && r.expires < new Date().toISOString().slice(0, 10);
  return (
    <>
      <p>
        Due {r.due} ·{" "}
        {r.completed ? `Completed ${r.completed}` : "Completion pending"}
      </p>
      <p>
        {r.expires
          ? `Expires ${r.expires}${expired ? " · Expired" : ""}`
          : "No expiration date recorded"}
      </p>
      {r.evidence && <p>Completion evidence: {r.evidence}</p>}
      {r.meta.status === "ASSIGNED" &&
        r.employeeId === context.actor.employeeId && (
          <TalentForm
            url={`/api/v1/talent/learning/${r.meta.id}/actions`}
            onSaved={onSaved}
            label="Record learning completion"
            build={(f) => ({
              version: r.meta.version,
              action: "COMPLETE",
              evidence: value(f, "evidence"),
              reason: value(f, "reason"),
            })}
          >
            <Notes
              name="evidence"
              label="Completion evidence and acknowledgement"
            />
          </TalentForm>
        )}
    </>
  );
}
