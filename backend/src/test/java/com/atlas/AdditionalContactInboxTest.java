package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.ContactModels.*;
import com.atlas.modules.corehr.application.port.in.AdditionalContactsUseCases;
import com.atlas.modules.corehr.domain.model.ChangeRequest;
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
class AdditionalContactInboxTest {
  @Autowired AdditionalContactsUseCases contacts;
  @Autowired JdbcTemplate db;

  Submit command(String name) {
    return new Submit(
        List.of(new Input("Synthetic " + name, "Friend", "+1 555 010 7801")),
        LocalDate.now(ZoneOffset.UTC),
        "Contact queue request",
        BigDecimal.ZERO,
        UUID.randomUUID().toString());
  }

  Query query(String status, int page, int size) {
    return new Query(status, page, size);
  }

  Review reject() {
    return new Review(
        "REJECTED", "Independent queue review", BigDecimal.ZERO, UUID.randomUUID().toString());
  }

  @Test
  void ownAndReviewScopesExcludeOtherTenantsAndNonIndependentEmployeeAliases() {
    var hrCreated = contacts.submit("amelia", "E003", command("HR-created"), "created");
    var ownHr = contacts.submit("amelia", "E001", command("own-HR"), "created");
    contacts.submit("outsider", "E900", command("other-tenant"), "created");
    db.update(
        "insert into identity_membership(subject,tenant,employee_id,name,role) values('inbox-hr-alias','atlas','E001','Synthetic alias','HR')");
    for (var subject : List.of("amelia", "inbox-hr-alias")) {
      assertEquals(0, contacts.inbox(subject, "REVIEW", query("ALL", 0, 8)).total());
      assertEquals(
          ownHr.id(), contacts.inbox(subject, "OWN", query("ALL", 0, 8)).items().getFirst().id());
    }
    var eligible = contacts.inbox("olivia", "REVIEW", query("SUBMITTED", 0, 8));
    assertEquals(2, eligible.total());
    assertEquals(
        Set.of(hrCreated.id(), ownHr.id()),
        new HashSet<>(eligible.items().stream().map(r -> r.id()).toList()));
    assertEquals(
        hrCreated.id(),
        contacts.inbox("sophia", "OWN", query("ALL", 0, 8)).items().getFirst().id());
    assertEquals(1, contacts.inbox("outsider", "OWN", query("ALL", 0, 8)).total());
    for (var subject : List.of("sophia", "marcus", "outsider"))
      assertThrows(
          ChangeRequest.Denied.class, () -> contacts.inbox(subject, "REVIEW", query("ALL", 0, 8)));
    assertEquals(0, contacts.inbox("marcus", "OWN", query("ALL", 0, 8)).total());
    assertThrows(
        IllegalArgumentException.class, () -> contacts.inbox("amelia", "E003", query("ALL", 0, 8)));
  }

  @Test
  void inboxPaginationAndFiltersAreCompleteAndStableAcrossDecisions() {
    var ids = new HashSet<String>();
    for (int i = 0; i < 11; i++) {
      var r = contacts.submit("sophia", "E003", command("attempt-" + i), "submitted");
      ids.add(r.id());
      contacts.review("amelia", "E003", r.id(), reject(), "rejected");
    }
    var first = contacts.inbox("amelia", "REVIEW", query("REJECTED", 0, 8));
    var last = contacts.inbox("amelia", "REVIEW", query("REJECTED", 1, 8));
    assertEquals(11, first.total());
    assertEquals(11, last.total());
    assertEquals(8, first.items().size());
    assertEquals(3, last.items().size());
    var actual = new HashSet<String>();
    first.items().forEach(r -> actual.add(r.id()));
    last.items().forEach(r -> actual.add(r.id()));
    assertEquals(ids, actual);
    assertEquals(first, contacts.inbox("amelia", "REVIEW", query("REJECTED", 0, 8)));
    assertEquals(0, contacts.inbox("amelia", "REVIEW", query("SUBMITTED", 0, 8)).total());
    assertEquals(0, contacts.inbox("amelia", "REVIEW", query("ALL", 2, 8)).items().size());
    assertEquals(11, contacts.inbox("sophia", "OWN", query("ALL", 0, 8)).total());
    assertEquals(0, contacts.inbox("amelia", "OWN", query("ALL", 0, 8)).total());
    db.execute("set constraints all immediate");
  }
}
