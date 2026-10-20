package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.timekeeping.application.model.TimeModels.*;
import com.atlas.modules.timekeeping.application.port.out.*;
import com.atlas.modules.timekeeping.application.usecase.TimeService;
import com.atlas.shared.domain.CommandConflict;
import java.lang.reflect.Proxy;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TimeIdentityRaceTest {
  @Test
  void identityChangeAcrossLockIsRejectedBeforeReadingReceiptsOrWriting() {
    var calls = new AtomicInteger();
    var before =
        new Actor(
            "subject", "first-tenant", "employee", "EMPLOYEE", "Active", LocalDate.of(2020, 1, 1));
    var after =
        new Actor(
            "subject", "second-tenant", "other", "EMPLOYEE", "Active", LocalDate.of(2020, 1, 1));
    TimeWorkforce workforce =
        new TimeWorkforce() {
          public Actor self(String subject) {
            return calls.getAndIncrement() == 0 ? before : after;
          }

          public Actor employee(String s, String e) {
            throw new AssertionError("Unexpected workforce lookup");
          }

          public Scope scope(String s) {
            throw new AssertionError("Unexpected scope lookup");
          }
        };
    TimeStore store =
        (TimeStore)
            Proxy.newProxyInstance(
                TimeStore.class.getClassLoader(),
                new Class<?>[] {TimeStore.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("lock")) {
                    assertEquals("first-tenant", args[0]);
                    assertEquals("employee", args[1]);
                    return null;
                  }
                  throw new AssertionError(
                      "No receipt or mutation after context switch: " + method.getName());
                });
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(Supplier<T> op) {
            return op.get();
          }
        };
    var service = new TimeService(store, workforce, tx, tx, Clock.systemUTC());
    assertThrows(
        CommandConflict.class,
        () ->
            service.punch(
                "subject", "IN", new Punch("Etc/UTC", "unique-command"), "identity-race"));
  }
}
