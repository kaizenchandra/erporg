package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.port.out.*;
import com.atlas.modules.timekeeping.application.usecase.TimeService;
import com.atlas.shared.domain.InputValidation;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TimeReportLimitTest {
  @Test
  void oversizedReportsFailWithoutReturningOrAuditingAPartialExport() {
    var count = new AtomicInteger(1000);
    var a = new Actor("hr", "tenant", "hr-worker", "HR", "Active", LocalDate.of(2020, 1, 1));
    TimeWorkforce workforce =
        new TimeWorkforce() {
          public Actor self(String s) {
            return a;
          }

          public Actor employee(String s, String id) {
            throw new AssertionError("No explicit worker filter");
          }

          public Scope scope(String s) {
            return new Scope(a, List.of(), true);
          }
        };
    var w = LocalDate.of(2025, 1, 6);
    var row = new ReportRow("sheet", "worker", w, "APPROVED", 2, 60, 60);
    TimeStore store =
        (TimeStore)
            Proxy.newProxyInstance(
                TimeStore.class.getClassLoader(),
                new Class<?>[] {TimeStore.class},
                (p, m, args) -> {
                  if (m.getName().equals("lock")) return null;
                  if (m.getName().equals("report")) {
                    assertEquals("tenant", args[0]);
                    assertEquals(true, args[2]);
                    return Collections.nCopies(count.get(), row);
                  }
                  throw new AssertionError(
                      "Oversized exports must not produce audit success: " + m.getName());
                });
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> work) {
            return work.get();
          }
        };
    var service = new TimeService(store, workforce, tx, tx, Clock.systemUTC());
    var q = new ReportQuery(w, w, "", "ALL");
    assertEquals(60000, service.report("hr", q).totalMinutes());
    count.set(1001);
    assertThrows(InputValidation.class, () -> service.report("hr", q));
    assertThrows(InputValidation.class, () -> service.exportReport("hr", q, "too-large"));
  }
}
