package com.atlas.modules.timekeeping.application.usecase;

import static com.atlas.modules.timekeeping.application.usecase.TimeCommandRules.*;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.port.out.*;
import com.atlas.modules.timekeeping.domain.model.TimePolicy;
import java.time.*;
import java.util.*;

/** Bounded, current-scope reporting over timekeeping-owned records only. */
final class TimeReportService {
  private final TimeStore store;
  private final TimeWorkforce workforce;
  private final UnitOfWork reads, writes;
  private final Clock clock;

  TimeReportService(
      TimeStore store, TimeWorkforce workforce, UnitOfWork reads, UnitOfWork writes, Clock clock) {
    this.store = store;
    this.workforce = workforce;
    this.reads = reads;
    this.writes = writes;
    this.clock = clock;
  }

  private Instant now() {
    return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
  }

  public Report report(String subject, ReportQuery query) {
    return reads.run(() -> reportRows(subject, query));
  }

  private Report reportRows(String subject, ReportQuery q) {
    TimePolicy.require(
        q != null && q.from() != null && q.through() != null,
        "period",
        "Choose first and last reporting weeks.");
    TimePolicy.week(q.from());
    TimePolicy.week(q.through());
    TimePolicy.require(
        !q.through().isBefore(q.from())
            && java.time.temporal.ChronoUnit.DAYS.between(q.from(), q.through()) <= 364,
        "period",
        "Choose an ordered range of at most 53 reporting weeks.");
    var status = q.status() == null ? "ALL" : q.status();
    TimePolicy.require(
        Set.of("ALL", "DRAFT", "SUBMITTED", "APPROVED", "REJECTED", "RETURNED", "CORRECTED")
            .contains(status),
        "status",
        "Choose a valid status.");
    var employee = q.employeeId() == null ? "" : q.employeeId().strip();
    var a = workforce.self(subject);
    if (!employee.isEmpty()) workforce.employee(subject, employee);
    var scope = workforce.scope(subject);
    var ids = new HashSet<>(scope.employeeIds());
    ids.add(a.employeeId());
    var rows =
        store.report(
            a.tenant(),
            List.copyOf(ids),
            scope.all(),
            new ReportQuery(q.from(), q.through(), employee, status));
    TimePolicy.require(
        rows.size() <= 1000,
        "period",
        "More than 1,000 timesheets match. Narrow the week, worker or status filter; no partial report was exported.");
    return new Report(
        rows,
        rows.stream().mapToInt(ReportRow::totalMinutes).sum(),
        rows.stream().mapToInt(ReportRow::allocatedMinutes).sum(),
        now());
  }

  public Report exportReport(String subject, ReportQuery query, String correlation) {
    return writes.run(
        () -> {
          var a = workforce.self(subject);
          store.lock(a.tenant(), a.employeeId());
          sameIdentity(a, workforce.self(subject));
          var report = reportRows(subject, query);
          store.audit(
              a.tenant(),
              a.employeeId(),
              UUID.randomUUID().toString(),
              "REPORT_EXPORTED",
              a.subject(),
              "Authorized filtered time report",
              query,
              Map.of(
                  "rows",
                  report.items().size(),
                  "totalMinutes",
                  report.totalMinutes(),
                  "allocatedMinutes",
                  report.allocatedMinutes()),
              now(),
              correlation);
          return report;
        });
  }
}
