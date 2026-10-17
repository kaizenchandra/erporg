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
@Import(RehireAccessHttpTest.TimeConfiguration.class)
class RehireAccessHttpTest {
  static final String SCHEMA =
      "atlas_rehire_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

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
  @Autowired ResignationUseCases resignations;
  @Autowired EmploymentEndUseCases ends;
  @Autowired RehireUseCases rehires;
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
  void returningOnboardingNeverRevivesUnusedSessionsAliasesOrPrivilegedGrants() throws Exception {
    people.decide(
        people.actor("amelia"),
        "demo-request-001",
        new Commands.Decide("REJECTED", "Resolve old change"),
        "resolved");
    db.update("update identity_membership set role='HR' where subject='sophia'");
    db.update("update identity_membership set employee_id='E003',role='HR' where subject='marcus'");
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
  }
}
