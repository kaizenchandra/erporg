package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.LegalEntityModels.*;
import com.atlas.modules.corehr.application.model.LegalEntityRetirementModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
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
class LegalEntityRetirementWorkflowTest {
  @Autowired LegalEntitiesUseCases entities;
  @Autowired LegalEntityRetirementUseCases retirement;
  @Autowired PeopleUseCases people;
  @Autowired JdbcTemplate db;

  Actor hr() {
    return people.actor("amelia");
  }

  Actor reviewer() {
    return people.actor("olivia");
  }

  LocalDate today() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  String key() {
    return UUID.randomUUID().toString();
  }

  void create(String code) {
    entities.create(
        hr(),
        new Create(
            new Input(code, "Declared Reference", "GB", "GBP", "Europe/London", today()),
            "Synthetic reference",
            key()),
        "creation");
  }

  Submit submit(LocalDate date, long version) {
    return new Submit(date, "Retire declared reference", BigDecimal.valueOf(version), key());
  }

  Review approve() {
    return new Review("APPROVED", "Independent reference review", BigDecimal.ZERO, key());
  }

  @Test
  void approvedRetirementRetainsCreationAndStableRequestDecisionReceipts() {
    create("RETIRE");
    var c = submit(today(), 0);
    var p = retirement.submit(hr(), "RETIRE", c, "proposal");
    assertEquals(p, retirement.submit(hr(), "RETIRE", c, "replay"));
    assertThrows(
        ChangeRequest.Denied.class,
        () -> retirement.decide(hr(), "RETIRE", p.id(), approve(), "self"));
    var review = approve();
    var d = retirement.decide(reviewer(), "RETIRE", p.id(), review, "decision");
    assertEquals(d, retirement.decide(reviewer(), "RETIRE", p.id(), review, "late-review"));
    assertEquals(p, retirement.submit(hr(), "RETIRE", c, "late-proposal"));
    var ctx = retirement.context(hr(), "RETIRE");
    assertEquals(today(), ctx.retirementDate());
    assertEquals(2, ctx.version());
    assertEquals("amelia", ctx.reference().createdBy());
    assertEquals(0, entities.list(hr(), new Query("RETIRE", "CURRENT", 0, 10)).total());
    var all = entities.list(hr(), new Query("RETIRE", "ALL", 0, 10));
    assertEquals(1, all.total());
    assertEquals(today(), all.retirementDates().get("RETIRE"));
    assertEquals(1, entities.list(hr(), new Query("RETIRE", "RETIRED", 0, 10)).total());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> retirement.submit(hr(), "RETIRE", submit(today(), 2), "duplicate"));
  }

  @Test
  void futureApprovalDoesNotRetireEarlyAndRejectionPermitsNewAttempt() {
    create("FUTURE");
    var first = retirement.submit(hr(), "FUTURE", submit(today().plusDays(5), 0), "first");
    retirement.decide(
        reviewer(),
        "FUTURE",
        first.id(),
        new Review("REJECTED", "Clarify proposed date", BigDecimal.ZERO, key()),
        "reject");
    assertNull(retirement.context(hr(), "FUTURE").retirementDate());
    var second = retirement.submit(hr(), "FUTURE", submit(today().plusDays(7), 2), "second");
    retirement.decide(reviewer(), "FUTURE", second.id(), approve(), "approve");
    assertEquals(1, entities.list(hr(), new Query("FUTURE", "CURRENT", 0, 10)).total());
    assertEquals(0, entities.list(hr(), new Query("FUTURE", "RETIRED", 0, 10)).total());
    var store =
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore(
            org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource()));
    assertEquals(
        1, store.list("atlas", new Query("FUTURE", "RETIRED", 0, 10), today().plusDays(7)).total());
    assertEquals(
        0, store.list("atlas", new Query("FUTURE", "CURRENT", 0, 10), today().plusDays(7)).total());
  }

  @Test
  void deniesRolesTenantAliasAndStaleFractionalVersions() {
    create("SCOPE");
    for (String user : List.of("sophia", "marcus", "outsider")) {
      var actor = people.actor(user);
      assertThrows(ChangeRequest.Denied.class, () -> retirement.context(actor, "SCOPE"));
      assertThrows(
          ChangeRequest.Denied.class,
          () -> retirement.submit(actor, "SCOPE", submit(today(), 0), "denied"));
    }
    assertThrows(
        ChangeRequest.Denied.class,
        () -> retirement.context(new Actor("other", "other", "E900", "Other HR", "HR"), "SCOPE"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            retirement.submit(
                hr(),
                "SCOPE",
                new Submit(today(), "Reason", new BigDecimal("0.1"), key()),
                "fractional"));
    var p = retirement.submit(hr(), "SCOPE", submit(today(), 0), "pending");
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> retirement.submit(hr(), "SCOPE", submit(today(), 0), "stale"));
    assertThrows(
        ChangeRequest.Denied.class,
        () ->
            retirement.decide(
                new Actor("alias", "atlas", "E001", "Alias HR", "HR"),
                "SCOPE",
                p.id(),
                approve(),
                "alias"));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            retirement.decide(
                reviewer(),
                "SCOPE",
                p.id(),
                new Review("APPROVED", "Reason", null, key()),
                "missing-version"));
  }

  @Test
  void sqlRejectsSelfReviewMissingPriorRejectionAndHistoryMutation() {
    create("SQL-GUARD");
    var p = retirement.submit(hr(), "SQL-GUARD", submit(today(), 0), "proposal");
    db.execute("savepoint retirement_probe");
    db.update(
        "insert into hr_legal_entity_retirement_decision(tenant,request_id,code,decision,comment,actor,actor_employee_id,recorded_at,correlation_id,idempotency_key) values('atlas',?,'SQL-GUARD','APPROVED','Invalid self review','amelia','E001',clock_timestamp(),'sql-probe','invalid-self-decision')",
        p.id());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint retirement_probe");
    db.execute("savepoint retirement_probe");
    db.update(
        "insert into hr_legal_entity_retirement_request select (jsonb_populate_record(null::hr_legal_entity_retirement_request,to_jsonb(r)||jsonb_build_object('id',?,'ordinal',2,'source_version',2,'idempotency_key','invalid-next-attempt'))).* from hr_legal_entity_retirement_request r where r.id=?",
        key(),
        p.id());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> db.execute("set constraints all immediate"));
    db.execute("rollback to savepoint retirement_probe");
    for (String sql :
        List.of(
            "update hr_legal_entity_retirement_request set reason=reason",
            "delete from hr_legal_entity_retirement_request",
            "truncate hr_legal_entity_retirement_request cascade")) {
      db.execute("savepoint retirement_probe");
      assertThrows(org.springframework.dao.DataAccessException.class, () -> db.execute(sql));
      db.execute("rollback to savepoint retirement_probe");
    }
  }

  @Test
  void elapsedProposalCanBeRejectedAndLateReplayRemainsStable() {
    create("EXPIRED");
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
          }
        };
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource());
    var start = Instant.now().plusSeconds(5);
    var service =
        new com.atlas.modules.corehr.application.usecase.LegalEntityRetirementService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityRetirementStore(
                jdbc),
            direct,
            direct,
            Clock.fixed(start, ZoneOffset.UTC));
    var c = submit(start.atOffset(ZoneOffset.UTC).toLocalDate(), 0);
    var proposal = service.submit(hr(), "EXPIRED", c, "submit");
    var later =
        new com.atlas.modules.corehr.application.usecase.LegalEntityRetirementService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore(jdbc),
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityRetirementStore(
                jdbc),
            direct,
            direct,
            Clock.fixed(start.plusSeconds(86400 * 2), ZoneOffset.UTC));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> later.decide(reviewer(), "EXPIRED", proposal.id(), approve(), "elapsed"));
    var review = new Review("REJECTED", "Elapsed date; request a new date", BigDecimal.ZERO, key());
    var rejected = later.decide(reviewer(), "EXPIRED", proposal.id(), review, "rejected");
    assertEquals(proposal, later.submit(hr(), "EXPIRED", c, "late-replay"));
    assertEquals(
        rejected, later.decide(reviewer(), "EXPIRED", proposal.id(), review, "decision-replay"));
  }

  @Test
  void rejectionHistoryIsBoundedAndOrdinalVersionsAreContiguous() {
    create("BOUNDED");
    for (int n = 0; n < 20; n++) {
      var p = retirement.submit(hr(), "BOUNDED", submit(today(), n * 2), "attempt");
      retirement.decide(
          reviewer(),
          "BOUNDED",
          p.id(),
          new Review("REJECTED", "Synthetic bounded attempt", BigDecimal.ZERO, key()),
          "reject");
    }
    assertEquals(40, retirement.context(hr(), "BOUNDED").version());
    assertEquals(20, retirement.context(hr(), "BOUNDED").requests().size());
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> retirement.submit(hr(), "BOUNDED", submit(today(), 40), "over-limit"));
  }

  @Autowired org.springframework.transaction.PlatformTransactionManager manager;

  long notificationCount(String subject) {
    return db.queryForObject(
        "select count(*) from hr_notification where tenant='atlas' and subject=? and message like '%declared-reference retirement%'",
        Long.class, subject);
  }

  void membership(String subject, Actor employee, String role) {
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values(?,?,?,?,?)",
        subject,
        employee.tenant(),
        employee.employeeId(),
        "Synthetic membership",
        role);
  }

  @Test
  void retirementPromptsExcludeAuthorAliasesAndOutcomeBelongsOnlyToOriginalSubject() {
    create("NOTICE");
    membership("retirement-author-alias", hr(), "HR");
    membership("retirement-review-alias", reviewer(), "HR");
    var subjects =
        List.of(
            "amelia",
            "olivia",
            "retirement-author-alias",
            "retirement-review-alias",
            "sophia",
            "outsider");
    var before = new HashMap<String, Long>();
    subjects.forEach(s -> before.put(s, notificationCount(s)));
    var command = submit(today(), 0);
    var proposal = retirement.submit(hr(), "NOTICE", command, "private-correlation");
    assertEquals(proposal, retirement.submit(hr(), "NOTICE", command, "retry"));
    for (String s : subjects)
      assertEquals(
          before.get(s) + (Set.of("olivia", "retirement-review-alias").contains(s) ? 1 : 0),
          notificationCount(s));
    var review = approve();
    retirement.decide(reviewer(), "NOTICE", proposal.id(), review, "review");
    retirement.decide(reviewer(), "NOTICE", proposal.id(), review, "retry");
    assertEquals(before.get("amelia") + 1, notificationCount("amelia"));
    assertEquals(
        before.get("retirement-author-alias"), notificationCount("retirement-author-alias"));
    assertEquals(before.get("olivia") + 1, notificationCount("olivia"));
    var messages =
        db.queryForList(
            "select message from hr_notification where tenant='atlas' and message like '%declared-reference retirement%'",
            String.class);
    assertTrue(
        messages.stream()
            .noneMatch(
                m ->
                    m.contains("NOTICE")
                        || m.contains("private-correlation")
                        || m.contains(command.reason())
                        || m.contains(review.comment())));
  }

  @Test
  void decisionDoesNotNotifyDemotedOrReassignedOriginalRequester() {
    for (String scenario : List.of("DEMOTED", "REASSIGNED")) {
      create(scenario);
      var author = hr();
      var p = retirement.submit(author, scenario, submit(today(), 0), "submit");
      long before = notificationCount("amelia");
      if (scenario.equals("DEMOTED"))
        db.update("update identity_membership set role='EMPLOYEE' where subject='amelia'");
      else
        db.update(
            "update identity_membership set employee_id=? where subject='amelia'",
            reviewer().employeeId());
      retirement.decide(
          reviewer(),
          scenario,
          p.id(),
          new Review("REJECTED", "Reference needs clarification", BigDecimal.ZERO, key()),
          "review");
      assertEquals(before, notificationCount("amelia"));
      db.update(
          "update identity_membership set role='HR',employee_id=? where subject='amelia'",
          author.employeeId());
    }
  }

  @Test
  void rejectedOutcomeIsDurableAndReplayDoesNotDeliverItAgain() {
    create("REJECT-NOTICE");
    long before = notificationCount("amelia");
    var p = retirement.submit(hr(), "REJECT-NOTICE", submit(today(), 0), "submit");
    var review = new Review("REJECTED", "Use a different date", BigDecimal.ZERO, key());
    retirement.decide(reviewer(), "REJECT-NOTICE", p.id(), review, "review");
    retirement.decide(reviewer(), "REJECT-NOTICE", p.id(), review, "replay");
    assertEquals(before + 1, notificationCount("amelia"));
    assertTrue(
        people.notifications(hr()).stream()
            .anyMatch(
                n ->
                    n.message()
                        .equals(
                            "Your declared-reference retirement was rejected. Open Settings for its decision history.")));
  }

  class NotificationProbe
      implements com.atlas.modules.corehr.application.port.out.LegalEntityRetirementStore {
    final com.atlas.modules.corehr.application.port.out.LegalEntityRetirementStore delegate;
    boolean fail;
    List<NotificationMember> candidates;

    NotificationProbe() {
      delegate =
          new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityRetirementStore(
              org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource()));
    }

    public InboxPage inbox(InboxScope scope, InboxQuery query) {
      return delegate.inbox(scope, query);
    }

    public List<NotificationMember> notificationCandidates(String tenant) {
      return candidates == null ? delegate.notificationCandidates(tenant) : candidates;
    }

    public void notify(String tenant, String subject, String message, Instant now) {
      delegate.notify(tenant, subject, message, now);
      if (fail) throw new IllegalStateException("Synthetic notification persistence failure");
    }

    public List<State> history(String tenant, String code) {
      return delegate.history(tenant, code);
    }

    public Optional<LegalEntityRetirement.Proposal> duplicate(
        String tenant, String actor, String key) {
      return delegate.duplicate(tenant, actor, key);
    }

    public Optional<LegalEntityRetirement.Decision> duplicateDecision(
        String tenant, String actor, String key) {
      return delegate.duplicateDecision(tenant, actor, key);
    }

    public void insert(String tenant, LegalEntityRetirement.Proposal p, String key) {
      delegate.insert(tenant, p, key);
    }

    public void insertDecision(
        String tenant, String code, LegalEntityRetirement.Decision d, String key) {
      delegate.insertDecision(tenant, code, d, key);
    }
  }

  LegalEntityRetirementUseCases notificationService(NotificationProbe store) {
    var tx = new org.springframework.transaction.support.TransactionTemplate(manager);
    tx.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
    com.atlas.modules.corehr.application.port.out.UnitOfWork boundary =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> work) {
            return tx.execute(s -> work.get());
          }
        };
    return new com.atlas.modules.corehr.application.usecase.LegalEntityRetirementService(
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore(
            org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource())),
        store,
        boundary,
        boundary,
        Clock.systemUTC());
  }

  @Test
  void notificationFailureRollsBackProposalAndDecisionAlongWithAllInboxWrites() {
    create("ATOMIC-NOTICE");
    var probe = new NotificationProbe();
    var service = notificationService(probe);
    long reviewerBefore = notificationCount("olivia"), authorBefore = notificationCount("amelia");
    var command = submit(today(), 0);
    probe.fail = true;
    assertThrows(
        IllegalStateException.class,
        () -> service.submit(hr(), "ATOMIC-NOTICE", command, "submit"));
    assertEquals(0, retirement.context(hr(), "ATOMIC-NOTICE").requests().size());
    assertEquals(reviewerBefore, notificationCount("olivia"));
    probe.fail = false;
    var p = service.submit(hr(), "ATOMIC-NOTICE", command, "submit-retry");
    probe.fail = true;
    var review = approve();
    assertThrows(
        IllegalStateException.class,
        () -> service.decide(reviewer(), "ATOMIC-NOTICE", p.id(), review, "review"));
    assertNull(retirement.context(hr(), "ATOMIC-NOTICE").requests().getFirst().decision());
    assertEquals(authorBefore, notificationCount("amelia"));
    probe.fail = false;
    service.decide(reviewer(), "ATOMIC-NOTICE", p.id(), review, "review-retry");
    assertEquals(authorBefore + 1, notificationCount("amelia"));
    assertEquals(reviewerBefore + 1, notificationCount("olivia"));
  }

  @Test
  void applicationRechecksCandidateScopeAndFailsOverflowWithoutPartialCommand() {
    create("BOUND-NOTICE");
    var probe = new NotificationProbe();
    var author = hr();
    var member = new NotificationMember("olivia", author.tenant(), reviewer().employeeId(), "HR");
    probe.candidates = java.util.Collections.nCopies(1001, member);
    var service = notificationService(probe);
    long before = notificationCount("olivia");
    var command = submit(today(), 0);
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> service.submit(author, "BOUND-NOTICE", command, "overflow"));
    assertEquals(0, retirement.context(author, "BOUND-NOTICE").requests().size());
    assertEquals(before, notificationCount("olivia"));
    probe.candidates =
        List.of(
            member,
            new NotificationMember("outsider", "another", "E900", "HR"),
            new NotificationMember("sophia", author.tenant(), "E103", "EMPLOYEE"),
            new NotificationMember(author.subject(), author.tenant(), author.employeeId(), "HR"));
    long excludedBefore = notificationCount("sophia") + notificationCount("outsider");
    service.submit(author, "BOUND-NOTICE", command, "safe-retry");
    assertEquals(before + 1, notificationCount("olivia"));
    assertEquals(excludedBefore, notificationCount("sophia") + notificationCount("outsider"));
  }

  @Test
  void inboxRequiresHrAndResolvesOwnReviewByCapturedSubjectAndEmployee() {
    create("QUEUE-SCOPE");
    var p = retirement.submit(hr(), "QUEUE-SCOPE", submit(today(), 0), "queue");
    var own = new InboxQuery("OWN", "PENDING", 0, 50);
    var review = new InboxQuery("REVIEW", "PENDING", 0, 50);
    assertTrue(
        retirement.inbox(hr(), own).items().stream()
            .anyMatch(s -> s.request().id().equals(p.id())));
    assertTrue(
        retirement.inbox(hr(), review).items().stream()
            .noneMatch(s -> s.request().id().equals(p.id())));
    assertTrue(
        retirement.inbox(reviewer(), review).items().stream()
            .anyMatch(s -> s.request().id().equals(p.id())));
    var alias = new Actor("queue-author-alias", hr().tenant(), hr().employeeId(), "Alias", "HR");
    for (var q : List.of(own, review))
      assertTrue(
          retirement.inbox(alias, q).items().stream()
              .noneMatch(s -> s.request().id().equals(p.id())));
    var reassigned =
        new Actor(hr().subject(), hr().tenant(), reviewer().employeeId(), "Moved subject", "HR");
    for (var q : List.of(own, review))
      assertTrue(
          retirement.inbox(reassigned, q).items().stream()
              .noneMatch(s -> s.request().id().equals(p.id())));
    for (String user : List.of("sophia", "marcus", "outsider"))
      assertThrows(ChangeRequest.Denied.class, () -> retirement.inbox(people.actor(user), review));
    var other = new Actor("other-hr", "other", "E900", "Other HR", "HR");
    assertEquals(0, retirement.inbox(other, review).total());
    assertTrue(retirement.inbox(other, review).items().isEmpty());
  }

  @Test
  void inboxFiltersRetainedDecisionContextAndProvidesDeterministicPages() {
    create("QUEUE-HISTORY");
    for (int n = 0; n < 12; n++) {
      var p = retirement.submit(hr(), "QUEUE-HISTORY", submit(today(), n * 2), "queue");
      retirement.decide(
          reviewer(),
          "QUEUE-HISTORY",
          p.id(),
          new Review("REJECTED", "Retained queue decision", BigDecimal.ZERO, key()),
          "review");
    }
    var pending =
        retirement.submit(hr(), "QUEUE-HISTORY", submit(today().plusDays(1), 24), "pending");
    var all = retirement.inbox(reviewer(), new InboxQuery("REVIEW", "ALL", 0, 50));
    var mine =
        all.items().stream().filter(s -> s.request().code().equals("QUEUE-HISTORY")).toList();
    assertEquals(13, mine.size());
    assertEquals(pending.id(), mine.getFirst().request().id());
    assertNull(mine.getFirst().decision());
    assertEquals(
        12,
        mine.stream()
            .filter(
                s ->
                    s.decision() != null
                        && s.decision().comment().equals("Retained queue decision"))
            .count());
    assertEquals(
        1,
        retirement.inbox(reviewer(), new InboxQuery("REVIEW", "PENDING", 0, 50)).items().stream()
            .filter(s -> s.request().code().equals("QUEUE-HISTORY"))
            .count());
    var first = retirement.inbox(reviewer(), new InboxQuery("REVIEW", "ALL", 0, 8));
    var last = retirement.inbox(reviewer(), new InboxQuery("REVIEW", "ALL", 1, 8));
    assertEquals(all.total(), first.total());
    assertEquals(all.total(), last.total());
    var actual =
        java.util.stream.Stream.concat(first.items().stream(), last.items().stream())
            .map(s -> s.request().id())
            .toList();
    assertEquals(all.items().stream().limit(16).map(s -> s.request().id()).toList(), actual);
    assertEquals(actual.size(), new HashSet<>(actual).size());
    assertEquals(
        0,
        retirement.inbox(reviewer(), new InboxQuery("REVIEW", "APPROVED", 0, 50)).items().stream()
            .filter(s -> s.request().code().equals("QUEUE-HISTORY"))
            .count());
    assertTrue(
        retirement.inbox(reviewer(), new InboxQuery("REVIEW", "ALL", 4000, 50)).items().isEmpty());
  }

  @Test
  void inboxOrdersEqualRecordedInstantsByImmutableIdAndCapsQueries() {
    create("QUEUE-TIE-A");
    create("QUEUE-TIE-B");
    var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(db.getDataSource());
    var store =
        new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityRetirementStore(jdbc);
    com.atlas.modules.corehr.application.port.out.UnitOfWork direct =
        new com.atlas.modules.corehr.application.port.out.UnitOfWork() {
          public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
          }
        };
    var fixed =
        new com.atlas.modules.corehr.application.usecase.LegalEntityRetirementService(
            new com.atlas.modules.corehr.adapter.out.persistence.JdbcLegalEntityStore(jdbc),
            store,
            direct,
            direct,
            Clock.fixed(Instant.now().plusSeconds(5), ZoneOffset.UTC));
    var one = fixed.submit(hr(), "QUEUE-TIE-A", submit(today(), 0), "tie-a");
    var two = fixed.submit(hr(), "QUEUE-TIE-B", submit(today(), 0), "tie-b");
    assertEquals(one.recordedAt(), two.recordedAt());
    var ids =
        fixed.inbox(reviewer(), new InboxQuery("REVIEW", "PENDING", 0, 50)).items().stream()
            .filter(s -> s.request().code().startsWith("QUEUE-TIE-"))
            .map(s -> s.request().id())
            .toList();
    assertEquals(
        java.util.stream.Stream.of(one.id(), two.id()).sorted(Comparator.reverseOrder()).toList(),
        ids);
    for (String scope : List.of("REVIEWER", "own", ""))
      assertThrows(ChangeRequest.Validation.class, () -> new InboxQuery(scope, "ALL", 0, 8));
    for (String status : List.of("SUBMITTED", "approved", ""))
      assertThrows(ChangeRequest.Validation.class, () -> new InboxQuery("OWN", status, 0, 8));
    for (int size : List.of(0, 51, 1000))
      assertThrows(ChangeRequest.Validation.class, () -> new InboxQuery("OWN", "ALL", 0, size));
    for (int page : List.of(-1, 4001))
      assertThrows(ChangeRequest.Validation.class, () -> new InboxQuery("OWN", "ALL", page, 8));
    assertThrows(ChangeRequest.Validation.class, () -> new InboxQuery(null, null, 0, 8));
  }
}
