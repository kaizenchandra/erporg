package com.atlas.modules.corehr.domain.model;

import java.time.*;
import java.util.*;

public record ExitTask(
    String id,
    String requestId,
    String employeeId,
    String assigneeId,
    int ordinal,
    String title,
    String instructions,
    LocalDate dueDate,
    String reason,
    String createdBy,
    Instant recordedAt,
    String status,
    long version,
    String completedBy,
    String completionComment,
    Instant completedAt) {
  public static void validate(String title, String instructions, String reason, LocalDate date) {
    var errors = new LinkedHashMap<String, String>();
    if (title == null || title.isBlank() || title.length() > 120)
      errors.put("title", "A title of 1–120 characters is required.");
    if (instructions == null || instructions.isBlank() || instructions.length() > 500)
      errors.put("instructions", "Instructions of 1–500 characters are required.");
    if (reason == null || reason.isBlank() || reason.length() > 500)
      errors.put("reason", "A reason of 1–500 characters is required.");
    if (date == null) errors.put("dueDate", "Due date is required.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
  }

  public static void schedule(LocalDate date, LocalDate today) {
    if (date.isBefore(today) || date.isAfter(today.plusYears(1)))
      throw new ChangeRequest.Validation(
          Map.of("dueDate", "Choose today or a date within the next year."));
  }

  public void ensureManagement(Actor actor, String action, String assignee, long expected) {
    if (!actor.hr() || actor.employeeId().equals(employeeId)) throw new ChangeRequest.Denied();
    if (!Set.of("REASSIGN", "CANCEL").contains(action == null ? "" : action))
      throw new IllegalArgumentException("Choose reassign or cancel.");
    if (!status.equals("OPEN") || version != expected || version >= 100)
      throw new ChangeRequest.Conflict(
          "Task changed, is terminal or reached 100 management events. Refresh the case.");
    if (action.equals("REASSIGN") && assigneeId.equals(assignee))
      throw new ChangeRequest.Validation(
          Map.of("assigneeId", "Choose a different assigned employee."));
  }

  public void ensureCompletion(Actor actor, long expected) {
    if (!actor.employeeId().equals(assigneeId)) throw new ChangeRequest.Denied();
    if (!status.equals("OPEN") || version != expected)
      throw new ChangeRequest.Conflict("Task changed. Refresh the task list.");
  }

  public boolean overdue(LocalDate today) {
    return status.equals("OPEN") && dueDate.isBefore(today);
  }
}
