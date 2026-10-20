package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.DepartmentParentModels.*;
import com.atlas.modules.corehr.application.port.in.DepartmentParentUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.DepartmentHierarchy;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;

public final class DepartmentParentService implements DepartmentParentUseCases {
  private final PeopleStore people;
  private final DepartmentParentStore store;
  private final UnitOfWork reads, writes;
  private final Clock clock;

  public DepartmentParentService(
      PeopleStore people,
      DepartmentParentStore store,
      UnitOfWork reads,
      UnitOfWork writes,
      Clock clock) {
    this.people = people;
    this.store = store;
    this.reads = reads;
    this.writes = writes;
    this.clock = clock;
  }

  private void code(String code) {
    if (code == null || !code.matches("[A-Z0-9-]{2,30}"))
      throw new IllegalArgumentException("Choose a valid department code.");
  }

  public Context context(String subject, String code, int page, int size) {
    code(code);
    if (page < 0 || page > 100000 || size < 1 || size > 50)
      throw new IllegalArgumentException("Use page0–100000 and size1–50.");
    return reads.run(
        () -> {
          var date = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
          var actor = people.actor(subject, date);
          if (!actor.hr()) throw new AccessDenied();
          var snapshot = store.find(actor.tenant(), code, date).orElseThrow(AccessDenied::new);
          return new Context(
              snapshot,
              date,
              store.history(actor.tenant(), code, page, size),
              store.total(actor.tenant(), code),
              page,
              size);
        });
  }

  public Change correct(String subject, String code, Command command, String correlation) {
    code(code);
    return writes.run(
        () -> {
          var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
          var date = LocalDate.ofInstant(now, ZoneOffset.UTC);
          var actor = people.actor(subject, date);
          if (!actor.hr()) throw new AccessDenied();
          long expected;
          try {
            if (command.version() == null || command.version().signum() < 0)
              throw new ArithmeticException();
            expected = command.version().longValueExact();
          } catch (ArithmeticException e) {
            throw new InputValidation(
                Map.of("version", "An exact nonnegative current revision is required."));
          }
          if (command.parentCode() != null && !command.parentCode().matches("[A-Z0-9-]{2,30}"))
            throw new InputValidation(Map.of("parentCode", "Choose a department or root."));
          if (command.reason() == null
              || command.reason().isBlank()
              || command.reason().length() > 500
              || command.reason().codePoints().anyMatch(Character::isISOControl))
            throw new InputValidation(
                Map.of("reason", "Enter a reason of1–500 characters without controls."));
          if (command.idempotencyKey() == null
              || !command.idempotencyKey().matches("[a-zA-Z0-9-]{8,80}"))
            throw new InputValidation(Map.of("idempotencyKey", "A valid command key is required."));
          if (command.effectiveDate() == null)
            throw new InputValidation(
                Map.of("effectiveDate", "Reload a valid current hierarchy context."));
          store.lock(actor.tenant());
          var duplicate =
              store.duplicate(actor.tenant(), actor.subject(), command.idempotencyKey());
          if (duplicate.isPresent()) {
            var old = duplicate.get();
            if (!old.code().equals(code)
                || old.version() - 1 != expected
                || !Objects.equals(old.parentCode(), command.parentCode())
                || !Objects.equals(old.effectiveDate(), command.effectiveDate())
                || !old.reason().equals(command.reason().trim()))
              throw new CommandConflict(
                  "Command key was used for different parent correction data.");
            return old;
          }
          if (!date.equals(command.effectiveDate()))
            throw new CommandConflict(
                "The UTC business date changed or was omitted. Reload hierarchy context before correcting.");
          var current = store.find(actor.tenant(), code, date).orElseThrow(AccessDenied::new);
          if (current.version() != expected)
            throw new CommandConflict(
                "Department hierarchy changed. Refresh context before correcting.");
          if (store.latestDate(actor.tenant()).filter(d -> d.isAfter(date)).isPresent())
            throw new CommandConflict(
                "Hierarchy contains a later business date. Recheck the server clock before correcting.");
          DepartmentHierarchy.ensureCorrection(
              code, command.parentCode(), store.graph(actor.tenant(), date), date);
          var change =
              new Change(
                  code,
                  Math.addExact(expected, 1),
                  current.parentCode(),
                  command.parentCode(),
                  date,
                  now,
                  actor.subject(),
                  command.reason().trim(),
                  command.idempotencyKey(),
                  correlation);
          store.insert(actor.tenant(), change);
          return change;
        });
  }
}
