package com.atlas.modules.corehr.application.usecase;

import com.atlas.modules.corehr.application.model.EmployeeHistoryModels.*;
import com.atlas.modules.corehr.application.port.in.EmployeeHistoryUseCases;
import com.atlas.modules.corehr.application.port.out.*;
import com.atlas.modules.corehr.domain.model.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;

public final class EmployeeHistoryService implements EmployeeHistoryUseCases {
  private final PeopleStore people;
  private final EmployeeHistoryStore history;
  private final UnitOfWork reads;
  private final Clock clock;

  public EmployeeHistoryService(
      PeopleStore people, EmployeeHistoryStore history, UnitOfWork reads, Clock clock) {
    this.people = people;
    this.history = history;
    this.reads = reads;
    this.clock = clock;
  }

  private String fingerprint(Actor a, String id, List<String> fields, Query q) {
    var parts =
        List.of(
            a.tenant(),
            a.subject(),
            a.employeeId(),
            a.role(),
            id,
            String.join(",", fields),
            q.field(),
            q.action(),
            q.order(),
            Integer.toString(q.size()));
    var value =
        parts.stream()
            .map(s -> s.length() + ":" + s)
            .collect(java.util.stream.Collectors.joining());
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public Page history(Actor a, String id, Query q) {
    return reads.run(
        () -> {
          var employee = people.employee(a, id, LocalDate.now(clock), false);
          if (!a.canRead(employee)) throw new ChangeRequest.Denied();
          var fields =
              ChangeRequest.FIELDS.stream()
                  .filter(f -> a.canReadField(employee, f))
                  .sorted()
                  .toList();
          if (!q.field().equals("ALL") && !fields.contains(q.field()))
            throw new ChangeRequest.Denied();
          var scope = new Scope(a.tenant(), id, fields);
          var fingerprint = fingerprint(a, id, fields, q);
          if (q.cursor() != null
              && (!q.cursor().fingerprint().equals(fingerprint) || !history.anchorExists(scope, q)))
            throw new ChangeRequest.Validation(
                Map.of(
                    "cursor",
                    "History context changed or the cursor is invalid. Refresh history."));
          long through = q.cursor() == null ? history.watermark(scope, q) : q.cursor().throughId();
          long count = history.count(scope, q, through);
          var rows = history.read(scope, q, through, q.size() + 1);
          boolean more = rows.size() > q.size();
          var items = List.copyOf(rows.subList(0, Math.min(rows.size(), q.size())));
          Cursor next = null;
          if (more) {
            var last = items.getLast();
            next = new Cursor(through, Long.parseLong(last.id()), last.recordedAt(), fingerprint);
          }
          return new Page(items, count, q.size(), q.order(), fields, next);
        });
  }
}
