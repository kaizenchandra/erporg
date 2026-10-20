package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.BenefitsUseCases;
import com.atlas.shared.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class BenefitsWorkflowTest {
  @Autowired BenefitsUseCases benefits;
  @Autowired JdbcTemplate jdbc;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient client;
  @Autowired com.atlas.modules.corehr.api.WorkforceAccess workforce;

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  CreatePlan command(String code, String name, LocalDate start) {
    return new CreatePlan(
        code,
        name,
        "GBP",
        "25.5",
        "0",
        start,
        "Synthetic demo benefit policy",
        UUID.randomUUID().toString());
  }

  @Test
  void createAuditReplayAndDecimalSnapshot() {
    var c = command("BEN-TEST", "Demo benefit", today());
    var first = benefits.create("amelia", c, "benefit-create");
    assertEquals("25.50", first.monthlyContribution());
    assertEquals("DEMONSTRATION", first.mode());
    assertEquals(first, benefits.create("amelia", c, "repeat"));
    assertTrue(
        benefits.history("amelia").stream()
            .anyMatch(
                a ->
                    a.code().equals(c.code())
                        && a.correlationId().equals("benefit-create")
                        && a.reason().equals(c.reason())));
    assertThrows(
        CommandConflict.class,
        () ->
            benefits.create(
                "amelia",
                new CreatePlan(
                    c.code(),
                    c.name(),
                    c.currency(),
                    "25.51",
                    c.minimumServiceDays(),
                    c.effectiveFrom(),
                    c.reason(),
                    c.idempotencyKey()),
                "changed"));
    assertEquals(1, jdbc.queryForObject("select count(*) from benefit_plan_audit", Integer.class));
  }

  @Test
  void roleTenantAndEmploymentScopes() {
    var c = command("BEN-SCOPE", "Scope benefit", today());
    for (String user : List.of("sophia", "marcus", "outsider"))
      assertThrows(AccessDenied.class, () -> benefits.create(user, c, "denied"));
    benefits.create("amelia", c, "create");
    assertEquals(1, benefits.plans("sophia", new PlanQuery("", "ELIGIBLE", 0, 8, "asc")).total());
    assertEquals(0, benefits.plans("outsider", new PlanQuery("", "ALL", 0, 8, "asc")).total());
    assertThrows(AccessDenied.class, () -> benefits.history("sophia"));
    jdbc.update(
        "insert into identity_membership values('benefits-onboarding','atlas','E008','Synthetic Onboarding','EMPLOYEE')");
    var pending = benefits.plans("benefits-onboarding", new PlanQuery("", "ALL", 0, 8, "asc"));
    assertFalse(pending.items().getFirst().eligible());
    assertEquals(
        0,
        benefits.plans("benefits-onboarding", new PlanQuery("", "ELIGIBLE", 0, 8, "asc")).total());
  }

  @Test
  void futureConfigurationAndReplayAcrossDateBoundary() {
    var c = command("BEN-FUTURE", "Future benefit", today().plusDays(1));
    assertFalse(benefits.create("amelia", c, "future").eligible());
    var future =
        new com.atlas.modules.benefits.application.usecase.BenefitsService(
            new com.atlas.modules.benefits.adapter.out.persistence.JdbcPlanStore(client),
            new com.atlas.modules.benefits.adapter.out.integration.CoreHrWorkforceAdapter(
                workforce),
            new com.atlas.modules.benefits.application.port.out.UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> operation) {
                return operation.get();
              }
            },
            new com.atlas.modules.benefits.application.port.out.UnitOfWork() {
              public <T> T run(java.util.function.Supplier<T> operation) {
                return operation.get();
              }
            },
            Clock.offset(Clock.systemUTC(), Duration.ofDays(2)));
    assertTrue(
        future
            .plans("sophia", new PlanQuery("", "ELIGIBLE", 0, 8, "asc"))
            .items()
            .getFirst()
            .eligible());
    assertEquals(c.code(), future.create("amelia", c, "past-date-replay").code());
  }

  @Test
  void filteringPagingAndInvalidAmounts() {
    for (int i = 0; i < 12; i++)
      benefits.create(
          "amelia", command("BEN-" + i, "Plan " + String.format("%02d", i), today()), "page");
    var first = benefits.plans("sophia", new PlanQuery("Plan", "ELIGIBLE", 0, 8, "asc"));
    var last = benefits.plans("sophia", new PlanQuery("Plan", "ELIGIBLE", 1, 8, "asc"));
    assertEquals(12, first.total());
    assertEquals(8, first.items().size());
    assertEquals(4, last.items().size());
    assertEquals("Plan 08", last.items().getFirst().name());
    assertEquals(
        "Plan 11",
        benefits
            .plans("sophia", new PlanQuery("Plan", "ALL", 0, 8, "desc"))
            .items()
            .getFirst()
            .name());
    assertEquals(0, benefits.plans("sophia", new PlanQuery("%", "ALL", 0, 8, "asc")).total());
    assertThrows(
        InputValidation.class,
        () ->
            benefits.create(
                "amelia",
                new CreatePlan(
                    "BEN-BAD",
                    "Bad precision",
                    "GBP",
                    "1.234",
                    "0",
                    today(),
                    "Invalid precision",
                    UUID.randomUUID().toString()),
                "bad"));
    assertThrows(
        InputValidation.class,
        () ->
            benefits.create(
                "amelia",
                new CreatePlan(
                    "BEN-FRACTION",
                    "Bad days",
                    "GBP",
                    "1.00",
                    "1.5",
                    today(),
                    "Invalid days",
                    UUID.randomUUID().toString()),
                "fraction"));
  }

  @Test
  void oversizedCatalogueReturnsCompleteCountAndBoundedRows() {
    jdbc.update(
        "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) select 'atlas','BEN-BULK-'||n,'Synthetic bulk plan '||n,'GBP',10.00,0,?,'fixture','bulk-key-'||n from generate_series(1,1001)n",
        today());
    var result = benefits.plans("sophia", new PlanQuery("", "ELIGIBLE", 0, 8, "asc"));
    assertEquals(1001, result.total());
    assertEquals(8, result.items().size());
    assertEquals(
        1, benefits.plans("sophia", new PlanQuery("", "ELIGIBLE", 10, 100, "asc")).items().size());
  }

  @Test
  void databaseRejectsExcessMonetaryPrecisionWithoutRounding() {
    assertThrows(
        org.springframework.dao.DataIntegrityViolationException.class,
        () ->
            jdbc.update(
                "insert into benefit_plan(tenant,code,name,currency,monthly_contribution,minimum_service_days,effective_from,created_by,idempotency_key) values('atlas','BEN-PRECISION','Invalid precision','GBP',1.234,0,current_date,'fixture','precision-key')"));
  }

  @Test
  void databaseMakesConfigurationImmutable() {
    benefits.create("amelia", command("BEN-FIXED", "Fixed benefit", today()), "fixed");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> jdbc.update("update benefit_plan set name='Changed' where code='BEN-FIXED'"));
  }
}
