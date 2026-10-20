package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.finance.application.model.FinanceModels.*;
import com.atlas.modules.finance.application.port.out.FinancePorts.*;
import com.atlas.modules.finance.application.usecase.FinanceService;
import com.atlas.shared.domain.AccessDenied;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class FinanceIdentitySeparationTest {
  @Test
  void reassignedSubjectCannotPostItsPreviouslyPreparedOrApprovedJournal() {
    for (boolean preparedBySubject : List.of(true, false)) {
      var identity = new Identity("same-subject", "tenant", "replacement-employee");
      var date = LocalDate.of(2026, 1, 1);
      var journal =
          new Journal(
              "journal",
              "ENTITY",
              "USD",
              date,
              "MANUAL",
              "source",
              null,
              "APPROVED",
              2,
              preparedBySubject ? "same-subject" : "other-preparer",
              "former-preparer",
              preparedBySubject ? "other-reviewer" : "same-subject",
              "former-reviewer",
              List.of(),
              "Prior approved journal",
              Instant.EPOCH);
      References refs =
          new References() {
            public Identity self(String subject) {
              return identity;
            }

            public Reference entity(String subject, String code) {
              throw new AssertionError("Must deny before posting reference checks");
            }
          };
      Store store =
          (Store)
              Proxy.newProxyInstance(
                  Store.class.getClassLoader(),
                  new Class<?>[] {Store.class},
                  (p, m, args) ->
                      switch (m.getName()) {
                        case "grants" ->
                            List.of(new Grant("ENTITY", "READ"), new Grant("ENTITY", "POST"));
                        case "lock" -> null;
                        case "journal" -> Optional.of(journal);
                        case "receipt" -> Optional.empty();
                        case "ledger" ->
                            Optional.of(
                                new Ledger("ENTITY", "USD", date, List.of("COST"), List.of()));
                        default ->
                            throw new AssertionError(
                                "No mutation is permitted after identity reassignment: "
                                    + m.getName());
                      });
      Transactions tx =
          new Transactions() {
            public <T> T run(Supplier<T> work) {
              return work.get();
            }
          };
      var service = new FinanceService(store, refs, tx, tx, Clock.systemUTC());
      assertThrows(
          AccessDenied.class,
          () ->
              service.action(
                  "same-subject",
                  "journal",
                  new Action(
                      BigDecimal.valueOf(2),
                      "POST",
                      "Reassigned subject must remain independent",
                      "safe-key-12345"),
                  "identity-separation"));
    }
  }
}
