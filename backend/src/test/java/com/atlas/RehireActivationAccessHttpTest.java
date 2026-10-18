package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.EmploymentEndModels.*;
import com.atlas.modules.corehr.application.port.in.*;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(RehireActivationAccessHttpTest.TimeConfiguration.class)
class RehireActivationAccessHttpTest {
  static final String SCHEMA =
      "atlas_activate_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
  }

  @TestConfiguration
  static class TimeConfiguration {
    @Bean
    @Primary
    MutableClock testClock() {
      return new MutableClock();
    }
  }

  static class MutableClock extends Clock {
    volatile Instant time = LocalDate.now(ZoneOffset.UTC).atTime(12, 0).toInstant(ZoneOffset.UTC);

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return Clock.fixed(time, zone);
    }

    public Instant instant() {
      return time;
    }
  }

  @Autowired MutableClock clock;
  @Autowired PeopleUseCases people;
  @Autowired RehireAccessUseCases access;
  @Autowired ResignationUseCases resignations;
  @Autowired EmploymentEndUseCases ends;
  @Autowired RehireUseCases rehires;
  @Autowired RehireActivationUseCases activations;
  @Autowired JdbcTemplate db;
  @LocalServerPort int port;
  tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

  HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  HttpResponse<String> get(HttpClient c, String path) throws Exception {
    return c.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  int login(HttpClient c, String user) throws Exception {
    var t = json.readTree(get(c, "/api/csrf").body());
    return c.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/login"))
                .header(t.get("headerName").asText(), t.get("token").asText())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        "username=" + user + "&password=atlas-demo"))
                .build(),
            HttpResponse.BodyHandlers.ofString())
        .statusCode();
  }

  @Test
  void newGrantRequiresFreshEmployeeLoginAndRejectsPreEpisodeOrUnstampedSessions()
      throws Exception {
    people.decide(
        people.actor("amelia"),
        "demo-request-001",
        new Commands.Decide("REJECTED", "Resolve old change"),
        "resolved");
    db.update("update identity_membership set role='HR' where subject='sophia'");
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='marcus'");
    var neverUsedReplacement = client();
    assertEquals(204, login(neverUsedReplacement, "marcus"));
    var neverUsed = client();
    assertEquals(204, login(neverUsed, "sophia"));
    var unused = client();
    var alias = client();
    var hr = client();
    assertEquals(204, login(unused, "sophia"));
    assertEquals(204, login(alias, "marcus"));
    assertEquals(204, login(hr, "amelia"));
    var e = people.profile(people.actor("sophia"), "E003");
    var r =
        resignations.submit(
            "sophia",
            new ResignationCommands.Submit(
                LocalDate.now(clock).plusDays(5),
                "Synthetic former employment",
                BigDecimal.valueOf(e.version()),
                UUID.randomUUID().toString()),
            "submitted");
    resignations.review(
        "amelia",
        r.id(),
        new ResignationCommands.Review(
            "APPROVED", "Independent", BigDecimal.ZERO, UUID.randomUUID().toString()),
        "approved");
    var end =
        ends.confirm(
            "amelia",
            r.id(),
            new Confirm(
                "Confirm end", BigDecimal.valueOf(e.version()), UUID.randomUUID().toString()),
            "ended");
    clock.time = end.inactiveDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    assertEquals("Ended", people.profile(people.actor("amelia"), "E003").status());
    var start = LocalDate.now(clock).plusDays(3);
    var rehire =
        rehires.submit(
            "amelia",
            "E003",
            new RehireModels.Submit(
                start,
                "Returning specialist",
                "Engineering",
                "London",
                "E002",
                "Rehire into new onboarding episode",
                BigDecimal.valueOf(end.newVersion()),
                UUID.randomUUID().toString()),
            "rehire-request");
    rehires.review(
        "olivia",
        "E003",
        rehire.id(),
        new RehireModels.Review(
            "APPROVED",
            "Independent",
            BigDecimal.valueOf(end.newVersion()),
            UUID.randomUUID().toString()),
        "rehire-approved");
    clock.time = start.atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
    assertEquals(
        "Ended", json.readTree(get(hr, "/api/v1/employees/E003").body()).get("status").asText());
    clock.time = clock.time.plusNanos(1);
    var profile = json.readTree(get(hr, "/api/v1/employees/E003").body());
    assertEquals("Onboarding", profile.get("status").asText());
    assertEquals(start.toString(), profile.get("startDate").asText());
    assertEquals("Returning specialist", profile.get("title").asText());
    // These sessions were never used during the ended interval; they must still not revive.
    assertEquals(401, get(unused, "/api/v1/session").statusCode());
    assertEquals(401, get(alias, "/api/v1/session").statusCode());
    assertEquals(401, login(client(), "sophia"));
    assertEquals(401, login(client(), "marcus"));
    assertEquals(200, get(hr, "/api/v1/session").statusCode());
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from identity_membership where employee_id='E003' and role='HR'",
            Integer.class));
    var context = json.readTree(get(hr, "/api/v1/employees/E003/rehire").body());
    assertTrue(context.get("legacyAccessBlocked").asBoolean());
    assertEquals(2, context.get("episodes").size());
    assertEquals(
        3,
        db.queryForObject(
            "select count(*) from hr_rehire_handoff where status='PENDING'", Integer.class));
    var request =
        activations.submit(
            "amelia",
            "E003",
            new RehireActivationModels.Submit(
                "sophia",
                "Onboarding evidence reviewed",
                BigDecimal.valueOf(rehires.context("amelia", "E003").employeeVersion()),
                UUID.randomUUID().toString()),
            "completion-request");
    activations.review(
        "olivia",
        "E003",
        request.id(),
        new RehireActivationModels.Review(
            "APPROVED",
            "Independent Employee-only access approval",
            BigDecimal.valueOf(request.inputVersion()),
            UUID.randomUUID().toString()),
        "completed");
    assertEquals(401, get(neverUsed, "/api/v1/session").statusCode());
    assertEquals(401, login(client(), "marcus"));
    var fresh = client();
    assertEquals(204, login(fresh, "sophia"));
    var self = json.readTree(get(fresh, "/api/v1/session").body());
    assertEquals("EMPLOYEE", self.get("role").asText());
    assertEquals(
        "Active",
        json.readTree(get(fresh, "/api/v1/employees/E003").body()).get("status").asText());
    assertEquals(403, get(fresh, "/api/v1/employees/E001").statusCode());
    assertEquals(403, get(fresh, "/api/v1/employees/E003/rehire/activation").statusCode());
    // A missing server-side stamp cannot be repaired with an HTTP header.
    db.update(
        "delete from spring_session_attributes a using spring_session s where a.session_primary_id=s.primary_id and s.principal_name='sophia' and a.attribute_name='atlas.workforceEpoch'");
    var forged =
        fresh.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/session"))
                .header("X-Employment-Epoch", people.sessionEpoch("sophia"))
                .header("X-Role", "HR")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(401, forged.statusCode());
    assertEquals(204, login(fresh, "sophia"));
    var token = json.readTree(get(fresh, "/api/csrf").body());
    assertEquals(
        204,
        fresh
            .send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/logout"))
                    .header(token.get("headerName").asText(), token.get("token").asText())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .statusCode());
    assertEquals(401, get(fresh, "/api/v1/session").statusCode());
    var held = client();
    assertEquals(204, login(held, "sophia"));
    var current = access.context("amelia", "E003");
    var competing = client();
    assertEquals(204, login(competing, "olivia"));
    var firstToken = json.readTree(get(hr, "/api/csrf").body());
    var secondToken = json.readTree(get(competing, "/api/csrf").body());
    var uri =
        URI.create("http://127.0.0.1:" + port + "/api/v1/employees/E003/rehire/access/revoke");
    var firstBody =
        json.writeValueAsString(
            new RehireAccessModels.Revoke(
                current.activationId(),
                "Immediate security suspension",
                BigDecimal.valueOf(current.employeeVersion()),
                UUID.randomUUID().toString()));
    var secondBody =
        json.writeValueAsString(
            new RehireAccessModels.Revoke(
                current.activationId(),
                "Competing security suspension",
                BigDecimal.valueOf(current.employeeVersion()),
                UUID.randomUUID().toString()));
    var first =
        hr.sendAsync(
            HttpRequest.newBuilder(uri)
                .header(firstToken.get("headerName").asText(), firstToken.get("token").asText())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(firstBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var second =
        competing.sendAsync(
            HttpRequest.newBuilder(uri)
                .header(secondToken.get("headerName").asText(), secondToken.get("token").asText())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(secondBody))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    var statuses =
        new java.util.ArrayList<Integer>(
            List.of(first.get().statusCode(), second.get().statusCode()));
    java.util.Collections.sort(statuses);
    assertEquals(List.of(200, 409), statuses);
    assertEquals(
        1, db.queryForObject("select count(*) from hr_rehire_access_revocation", Integer.class));
    assertEquals(401, get(held, "/api/v1/session").statusCode());
    assertEquals(401, login(client(), "sophia"));
    assertEquals(401, login(client(), "marcus"));
    assertEquals(
        "Active", json.readTree(get(hr, "/api/v1/employees/E003").body()).get("status").asText());
    assertEquals(
        "REVOKED",
        json.readTree(get(hr, "/api/v1/employees/E003/rehire/access").body())
            .get("accessStatus")
            .asText());
    var replacementPath = "/api/v1/employees/E003/rehire/access/replacement";
    var replacementVersion =
        json.readTree(get(hr, replacementPath).body()).get("employeeVersion").asLong();
    var submitted =
        postAsync(
                hr,
                replacementPath,
                new IdentityReplacementModels.Submit(
                    "marcus",
                    "Configure different existing identity",
                    BigDecimal.valueOf(replacementVersion),
                    UUID.randomUUID().toString()))
            .get();
    assertEquals(200, submitted.statusCode());
    var replacementId = json.readTree(submitted.body()).get("id").asText();
    db.update(
        "update identity_membership set tenant='atlas',employee_id='E004',role='HR' where subject='outsider'");
    var third = client();
    assertEquals(204, login(third, "outsider"));
    var reviewPath = replacementPath + "/" + replacementId + "/review";
    var approvalA =
        postAsync(
            competing,
            reviewPath,
            new IdentityReplacementModels.Review(
                "APPROVED",
                "Independent review A",
                BigDecimal.valueOf(replacementVersion),
                UUID.randomUUID().toString()));
    var approvalB =
        postAsync(
            third,
            reviewPath,
            new IdentityReplacementModels.Review(
                "APPROVED",
                "Independent review B",
                BigDecimal.valueOf(replacementVersion),
                UUID.randomUUID().toString()));
    var reviewStatuses =
        new java.util.ArrayList<Integer>(
            List.of(approvalA.get().statusCode(), approvalB.get().statusCode()));
    java.util.Collections.sort(reviewStatuses);
    assertEquals(List.of(200, 409), reviewStatuses);
    assertEquals(401, get(neverUsedReplacement, "/api/v1/session").statusCode());
    assertEquals(401, login(client(), "sophia"));
    var newIdentity = client();
    assertEquals(204, login(newIdentity, "marcus"));
    assertEquals(
        "EMPLOYEE", json.readTree(get(newIdentity, "/api/v1/session").body()).get("role").asText());
    assertEquals(403, get(newIdentity, "/api/v1/employees/E001").statusCode());
    var newGrant = json.readTree(get(hr, "/api/v1/employees/E003/rehire/access").body());
    assertEquals(2, newGrant.get("grantNumber").asInt());
    assertEquals(replacementId, newGrant.get("activationId").asText());
    var revokedReplacement =
        postAsync(
                hr,
                "/api/v1/employees/E003/rehire/access/revoke",
                new RehireAccessModels.Revoke(
                    replacementId,
                    "Revoke replacement identity",
                    BigDecimal.valueOf(newGrant.get("employeeVersion").asLong()),
                    UUID.randomUUID().toString()))
            .get();
    assertEquals(200, revokedReplacement.statusCode());
    assertEquals(401, get(newIdentity, "/api/v1/session").statusCode());
    assertEquals(401, login(client(), "marcus"));
    assertEquals(401, login(client(), "sophia"));
    assertEquals(2, db.queryForObject("select count(*) from hr_local_access_grant", Integer.class));
    assertEquals(
        2, db.queryForObject("select count(*) from hr_rehire_access_revocation", Integer.class));
  }

  java.util.concurrent.CompletableFuture<HttpResponse<String>> postAsync(
      HttpClient client, String path, Object command) throws Exception {
    var t = json.readTree(get(client, "/api/csrf").body());
    return client.sendAsync(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(t.get("headerName").asText(), t.get("token").asText())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(command)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }
}
