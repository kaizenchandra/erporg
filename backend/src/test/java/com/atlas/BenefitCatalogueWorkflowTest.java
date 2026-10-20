package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.adapter.out.persistence.JdbcPlanStore;
import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.BenefitsUseCases;
import com.atlas.modules.benefits.application.port.out.*;
import com.atlas.modules.benefits.application.usecase.BenefitsService;
import com.atlas.modules.benefits.domain.model.BenefitPlan;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:postgresql://localhost:5438/atlas?currentSchema=atlas_test",
      "spring.flyway.default-schema=atlas_test",
      "spring.flyway.schemas=atlas_test"
    })
@ActiveProfiles("local")
@Transactional
class BenefitCatalogueWorkflowTest {
  @Autowired BenefitsUseCases benefits;
  @Autowired JdbcTemplate db;
  @Autowired JdbcClient client;

  void bulk() {
    db.update(
        "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) select 'atlas','BF-'||lpad(g::text,4,'0'),'Synthetic BF plan '||lpad(g::text,4,'0'),'GBP',12.50,0,date '2020-01-01','fixture','bf-key-'||g from generate_series(1,1005)g");
  }

  @Test
  void allAndEligiblePagesReachBeyond1000WithoutTruncatingCounts() {
    bulk();
    for (String filter : List.of("ALL", "ELIGIBLE")) {
      var all = new ArrayList<String>();
      for (int page = 0; page < 11; page++) {
        var result =
            benefits.plans("sophia", new PlanQuery("Synthetic BF", filter, page, 100, "asc"));
        assertEquals(1005, result.total());
        assertEquals(page == 10 ? 5 : 100, result.items().size());
        all.addAll(result.items().stream().map(PlanView::code).toList());
      }
      assertEquals(1005, new HashSet<>(all).size());
      assertEquals("BF-1005", all.getLast());
      assertEquals(
          "BF-1005",
          benefits
              .plans("sophia", new PlanQuery("Synthetic BF", filter, 0, 8, "desc"))
              .items()
              .getFirst()
              .code());
    }
    assertEquals(
        0, benefits.plans("outsider", new PlanQuery("BF-1005", "ALL", 0, 8, "asc")).total());
    assertEquals(
        1, benefits.plans("sophia", new PlanQuery("BF-1005", "ELIGIBLE", 0, 8, "asc")).total());
  }

  @Test
  void literalSearchDeterministicTiesAndEmptyPages() {
    for (String code : List.of("BF-TIE-B", "BF-TIE-A"))
      db.update(
          "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) values('atlas',?,'Literal 100%_\\ path 界','GBP',0,0,date '2020-01-01','fixture',?)",
          code, code);
    for (String search : List.of("100%_", "\\", "界", "literal"))
      for (String order : List.of("asc", "desc")) {
        var result = benefits.plans("sophia", new PlanQuery(search, "ALL", 0, 8, order));
        assertEquals(2, result.total());
        assertEquals(
            List.of("BF-TIE-A", "BF-TIE-B"), result.items().stream().map(PlanView::code).toList());
      }
    assertEquals(0, benefits.plans("sophia", new PlanQuery("100%X", "ALL", 0, 8, "asc")).total());
    assertTrue(
        benefits
            .plans("sophia", new PlanQuery("Literal", "ALL", 100000, 100, "asc"))
            .items()
            .isEmpty());
  }

  BenefitsService at(String status, LocalDate start, Clock clock) {
    WorkforcePort workforce =
        new WorkforcePort() {
          public BenefitContext self(String subject) {
            return new BenefitContext(subject, "atlas", "E003", "EMPLOYEE", status, start);
          }

          public BenefitContext employee(String subject, String employee) {
            throw new UnsupportedOperationException();
          }
        };
    UnitOfWork tx =
        new UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> s) {
            return s.get();
          }
        };
    return new BenefitsService(new JdbcPlanStore(client), workforce, tx, tx, clock);
  }

  @Test
  void databaseEligibilityMatchesDomainAcrossLeapServiceAndFutureBoundaries() {
    var start = LocalDate.of(2024, 2, 29);
    for (int g = 0; g < 48; g++)
      db.update(
          "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) values('atlas',?,?,'GBP',0,?,?,'fixture',?)",
          "BF-W-" + g,
          "Window " + g,
          g % 4,
          start.plusDays(g % 3),
          "bf-window-" + g);
    var plans =
        client
            .sql(
                "select code,name,currency,monthly_contribution,minimum_service_days,effective_from,rule_version from benefit_plan where tenant='atlas'")
            .query(BenefitPlan.class)
            .list();
    for (String status : List.of("Active", "On leave", "Onboarding", "Terminated"))
      for (int days : List.of(-1, 0, 1, 2, 3, 30)) {
        var day = start.plusDays(days);
        var service =
            at(
                status,
                start,
                Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
        var result = service.plans("fixture", new PlanQuery("", "ELIGIBLE", 0, 100, "asc"));
        var expected =
            plans.stream()
                .filter(p -> p.eligibility(status, start, day).eligible())
                .map(BenefitPlan::code)
                .sorted()
                .toList();
        assertEquals(expected, result.items().stream().map(PlanView::code).sorted().toList());
        assertEquals(expected.size(), result.total());
        assertTrue(result.items().stream().allMatch(PlanView::eligible));
        assertEquals(day, result.evaluatedOn());
      }
  }

  @Test
  void utcEvaluationDateDoesNotFollowClockZoneOrCrossMidnightWithinResponse() {
    db.update(
        "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) values('atlas','BF-MIDNIGHT','Midnight BF','GBP',0,0,date '2026-10-20','fixture','bf-midnight')");
    var instant = Instant.parse("2026-10-20T00:00:00Z");
    var before =
        at(
            "Active",
            LocalDate.of(2024, 1, 1),
            Clock.fixed(instant.minusNanos(1), ZoneId.of("Pacific/Auckland")));
    var after =
        at(
            "Active",
            LocalDate.of(2024, 1, 1),
            Clock.fixed(instant, ZoneId.of("America/Los_Angeles")));
    assertEquals(
        0, before.plans("fixture", new PlanQuery("BF-MIDNIGHT", "ELIGIBLE", 0, 8, "asc")).total());
    var result = after.plans("fixture", new PlanQuery("BF-MIDNIGHT", "ELIGIBLE", 0, 8, "asc"));
    assertEquals(1, result.total());
    assertEquals(LocalDate.of(2026, 10, 20), result.evaluatedOn());
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    Clock crossing =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return calls.getAndIncrement() == 0 ? instant.minusNanos(1) : instant;
          }
        };
    var snapshot =
        at("Active", LocalDate.of(2024, 1, 1), crossing)
            .plans("fixture", new PlanQuery("BF-MIDNIGHT", "ELIGIBLE", 0, 8, "asc"));
    assertEquals(0, snapshot.total());
    assertEquals(LocalDate.of(2026, 10, 19), snapshot.evaluatedOn());
    assertEquals(1, calls.get());
  }
}
