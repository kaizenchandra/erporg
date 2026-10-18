package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.ExitTaskModels.*;
import com.atlas.modules.corehr.application.port.in.ExitTaskUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ExitTaskService implements ExitTaskUseCases {
  private final ExitTaskStore store;
  private final ResignationStore resignations;
  private final PeopleStore people;
  private final UnitOfWork tx, reads;
  private final Clock clock;

  public ExitTaskService(
      ExitTaskStore store,
      ResignationStore resignations,
      PeopleStore people,
      UnitOfWork tx,
      UnitOfWork reads,
      Clock clock) {
    this.store = store;
    this.resignations = resignations;
    this.people = people;
    this.tx = tx;
    this.reads = reads;
    this.clock = clock;
  }

  private Actor actor(String subject) {
    return people.actor(subject, LocalDate.now(clock));
  }

  private long version(BigDecimal value) {
    try {
      if (value != null && value.signum() >= 0) return value.longValueExact();
    } catch (ArithmeticException ignored) {
    }
    throw new IllegalArgumentException("An exact nonnegative version is required.");
  }

  private void key(String key) {
    if (key == null || !key.matches("[a-zA-Z0-9-]{8,80}"))
      throw new IllegalArgumentException("A valid idempotency key is required.");
  }

  private void read(Actor actor, ExitTask task) {
    if (!actor.hr() && !actor.employeeId().equals(task.assigneeId()))
      throw new ChangeRequest.Denied();
  }

  private Item item(ExitTask task) {
    return new Item(task, task.overdue(LocalDate.now(clock)));
  }

  public Page mine(String subject, Query q) {
    return reads.run(
        () -> {
          var a = actor(subject);
          var rows = store.mine(a.tenant(), a.employeeId(), q);
          return new Page(
              rows.items().stream().map(this::item).toList(),
              rows.total(),
              q.page(),
              q.size(),
              LocalDate.now(clock));
        });
  }

  public Case forCase(String subject, String id) {
    return reads.run(
        () -> {
          var a = actor(subject);
          if (!a.hr()) throw new ChangeRequest.Denied();
          resignations.get(a.tenant(), id, false);

          var tasks = store.forCase(a.tenant(), id);
          return new Case(
              tasks.stream().map(this::item).toList(), tasks.size(), LocalDate.now(clock));
        });
  }

  public List<Audit> history(String subject, String id) {
    return reads.run(
        () -> {
          var a = actor(subject);
          read(a, store.task(a.tenant(), id, false));
          return store.history(a.tenant(), id);
        });
  }

  public Item create(String subject, String id, Create c, String correlation) {
    ExitTask.validate(c.title(), c.instructions(), c.reason(), c.dueDate());
    long v = version(c.version());
    key(c.idempotencyKey());
    if (c.assigneeId() == null || c.assigneeId().isBlank() || c.assigneeId().length() > 40)
      throw new ChangeRequest.Validation(
          Map.of("assigneeId", "A tenant employee identifier is required."));
    return tx.run(
        () -> {
          var a = actor(subject);
          if (!a.hr()) throw new ChangeRequest.Denied();
          people.lockReportingGraph(a.tenant());
          var r = resignations.get(a.tenant(), id, true);
          if (a.employeeId().equals(r.employeeId())) throw new ChangeRequest.Denied();
          if (!r.status().equals("APPROVED"))
            throw new ChangeRequest.Conflict(
                "Approve the resignation before assigning exit tasks.");
          var previous = store.duplicate(a.tenant(), subject, c.idempotencyKey());
          if (previous.isPresent()) {
            var old = previous.get();
            var t = old.task();
            if (!t.requestId().equals(id)
                || old.inputVersion() != v
                || !t.assigneeId().equals(c.assigneeId())
                || !t.title().equals(c.title().trim())
                || !t.instructions().equals(c.instructions().trim())
                || !t.reason().equals(c.reason().trim())
                || !t.dueDate().equals(c.dueDate()))
              throw new ChangeRequest.Conflict("Idempotency key was used for another exit task.");
            return item(store.task(a.tenant(), t.id(), false));
          }
          if (people.employmentEndDate(a.tenant(), r.employeeId()).isPresent())
            throw new ChangeRequest.Conflict(
                "Employment end is recorded; exit task coordination is closed.");
          var tasks = store.forCase(a.tenant(), id);
          if (tasks.size() != v || v >= 50)
            throw new ChangeRequest.Conflict(
                "Task list changed or reached 50 tasks. Refresh the case.");
          ExitTask.schedule(c.dueDate(), LocalDate.now(clock));
          people.employee(a, c.assigneeId(), LocalDate.now(clock), false);
          if (people
              .currentEpisodeEndDate(a.tenant(), c.assigneeId(), LocalDate.now(clock))
              .isPresent())
            throw new ChangeRequest.Validation(
                Map.of("assigneeId", "Choose an employee without a scheduled employment end."));
          if (!people.hasCurrentWorkspaceIdentity(a.tenant(), c.assigneeId(), LocalDate.now(clock)))
            throw new ChangeRequest.Validation(
                Map.of("assigneeId", "Choose an employee with current workspace access."));
          var task =
              new ExitTask(
                  UUID.randomUUID().toString(),
                  id,
                  r.employeeId(),
                  c.assigneeId(),
                  tasks.size() + 1,
                  c.title().trim(),
                  c.instructions().trim(),
                  c.dueDate(),
                  c.reason().trim(),
                  subject,
                  clock.instant(),
                  "OPEN",
                  0,
                  null,
                  null,
                  null);
          store.insert(a.tenant(), task, c.idempotencyKey(), v, correlation);
          return item(task);
        });
  }

  public Item complete(String subject, String id, Complete c, String correlation) {
    long v = version(c.version());
    key(c.idempotencyKey());
    if (c.comment() == null || c.comment().isBlank() || c.comment().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("comment", "A completion comment of 1–500 characters is required."));
    return tx.run(
        () -> {
          var a = actor(subject);
          var t = store.task(a.tenant(), id, true);
          if (!a.employeeId().equals(t.assigneeId())) throw new ChangeRequest.Denied();
          if (c.idempotencyKey().equals(store.completionKey(a.tenant(), id))) {
            if (!subject.equals(t.completedBy())
                || v != t.version() - 1
                || !c.comment().trim().equals(t.completionComment()))
              throw new ChangeRequest.Conflict("Idempotency key was used for another completion.");
            return item(t);
          }
          t.ensureCompletion(a, v);
          var done =
              new ExitTask(
                  t.id(),
                  t.requestId(),
                  t.employeeId(),
                  t.assigneeId(),
                  t.ordinal(),
                  t.title(),
                  t.instructions(),
                  t.dueDate(),
                  t.reason(),
                  t.createdBy(),
                  t.recordedAt(),
                  "COMPLETED",
                  t.version() + 1,
                  subject,
                  c.comment().trim(),
                  clock.instant());
          store.complete(a.tenant(), done, c.idempotencyKey(), correlation);
          return item(done);
        });
  }

  public List<Management> managementHistory(String subject, String id) {
    return reads.run(
        () -> {
          var a = actor(subject);
          if (!a.hr()) throw new ChangeRequest.Denied();
          store.task(a.tenant(), id, false);
          return store.managementHistory(a.tenant(), id);
        });
  }

  public Management manage(String subject, String id, Manage c, String correlation) {
    long v = version(c.version());
    key(c.idempotencyKey());
    if (c.reason() == null || c.reason().isBlank() || c.reason().length() > 500)
      throw new ChangeRequest.Validation(
          Map.of("reason", "A management reason of 1–500 characters is required."));
    if (!Set.of("REASSIGN", "CANCEL").contains(c.action() == null ? "" : c.action()))
      throw new IllegalArgumentException("Choose reassign or cancel.");
    if (c.action().equals("REASSIGN")
        && (c.assigneeId() == null || c.assigneeId().isBlank() || c.assigneeId().length() > 40))
      throw new ChangeRequest.Validation(
          Map.of("assigneeId", "A configured tenant employee identifier is required."));
    if (c.action().equals("CANCEL") && c.assigneeId() != null && !c.assigneeId().isEmpty())
      throw new ChangeRequest.Validation(
          Map.of("assigneeId", "Cancellation cannot change the assigned employee."));
    return tx.run(
        () -> {
          var a = actor(subject);
          if (!a.hr()) throw new ChangeRequest.Denied();
          people.lockReportingGraph(a.tenant());
          var initial = store.task(a.tenant(), id, false);
          var r = resignations.get(a.tenant(), initial.requestId(), true);
          var t = store.task(a.tenant(), id, true);
          if (a.employeeId().equals(t.employeeId())) throw new ChangeRequest.Denied();
          var previous = store.managementReplay(a.tenant(), subject, c.idempotencyKey());
          if (previous.isPresent()) {
            var e = previous.get();
            if (!e.taskId().equals(id)
                || !e.action().equals(c.action())
                || e.inputVersion() != v
                || !e.reason().equals(c.reason().trim())
                || (c.action().equals("REASSIGN") && !e.newAssigneeId().equals(c.assigneeId())))
              throw new ChangeRequest.Conflict(
                  "Idempotency key was used for another task management command.");
            return e;
          }
          if (people.employmentEndDate(a.tenant(), t.employeeId()).isPresent())
            throw new ChangeRequest.Conflict(
                "Employment end is recorded; exit task coordination is closed.");
          t.ensureManagement(a, c.action(), c.assigneeId(), v);
          if (!r.status().equals("APPROVED"))
            throw new ChangeRequest.Conflict("An approved resignation is required.");
          String assigned = t.assigneeId();
          if (c.action().equals("REASSIGN")) {
            people.employee(a, c.assigneeId(), LocalDate.now(clock), false);
            if (people
                .currentEpisodeEndDate(a.tenant(), c.assigneeId(), LocalDate.now(clock))
                .isPresent())
              throw new ChangeRequest.Validation(
                  Map.of("assigneeId", "Choose an employee without a scheduled employment end."));
            if (!people.hasCurrentWorkspaceIdentity(
                a.tenant(), c.assigneeId(), LocalDate.now(clock)))
              throw new ChangeRequest.Validation(
                  Map.of("assigneeId", "Choose an employee with current workspace access."));
            assigned = c.assigneeId();
          }
          var event =
              new Management(
                  t.id(),
                  Math.toIntExact(v + 1),
                  c.action(),
                  t.assigneeId(),
                  assigned,
                  "OPEN",
                  c.action().equals("CANCEL") ? "CANCELLED" : "OPEN",
                  subject,
                  a.employeeId(),
                  c.reason().trim(),
                  v,
                  clock.instant(),
                  correlation);
          store.manage(a.tenant(), event, c.idempotencyKey());
          return event;
        });
  }
}
