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
@Import(EndRescheduleAccessHttpTest.TimeConfiguration.class)
class EndRescheduleAccessHttpTest {
  static final String SCHEMA =
      "atlas_reschedule_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

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
  @Autowired EndRescheduleUseCases reschedules;
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
  void replacementKeepsAccessAtOriginalCutoffAndDeniesEveryIdentityAtNewCutoff() throws Exception {
    db.update(
        "update identity_membership set employee_id='E003',role='EMPLOYEE' where subject='marcus'");
    var first = client();
    var second = client();
    var alias = client();
    var hr = client();
    assertEquals(204, login(first, "sophia"));
    assertEquals(204, login(second, "sophia"));
    assertEquals(204, login(alias, "marcus"));
    assertEquals(204, login(hr, "amelia"));
    var date = LocalDate.now(clock).plusDays(5);
    var e = people.profile(people.actor("sophia"), "E003");
    var r =
        resignations.submit(
            "sophia",
            new ResignationCommands.Submit(
                date,
                "HTTP boundary test",
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
                "Confirm effective end",
                BigDecimal.valueOf(e.version()),
                UUID.randomUUID().toString()),
            "scheduled");

    var replacement =
        reschedules.submit(
            "amelia",
            r.id(),
            new EndRescheduleModels.Submit(
                date.plusDays(10),
                "Agreed later date",
                BigDecimal.valueOf(end.newVersion()),
                UUID.randomUUID().toString()),
            "replacement-request");
    reschedules.review(
        "olivia",
        r.id(),
        replacement.id(),
        new EndRescheduleModels.Review(
            "APPROVED",
            "Independent correction",
            BigDecimal.valueOf(end.newVersion()),
            UUID.randomUUID().toString()),
        "replacement-approved");
    clock.time = end.inactiveDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    for (var session : List.of(first, second, alias))
      assertEquals(200, get(session, "/api/v1/session").statusCode());
    var laterSession = client();
    var laterAlias = client();
    assertEquals(204, login(laterSession, "sophia"));
    assertEquals(204, login(laterAlias, "marcus"));
    assertEquals(
        "Active", json.readTree(get(hr, "/api/v1/employees/E003").body()).get("status").asText());
    clock.time =
        replacement
            .lastWorkingDate()
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()
            .minusNanos(1);
    assertEquals(200, get(first, "/api/v1/session").statusCode());
    clock.time = clock.time.plusNanos(1);
    for (var session : List.of(first, second, alias, laterSession, laterAlias))
      assertEquals(401, get(session, "/api/v1/session").statusCode());
    assertEquals(401, login(client(), "sophia"));
    assertEquals(401, login(client(), "marcus"));
    assertEquals(
        "Ended", json.readTree(get(hr, "/api/v1/employees/E003").body()).get("status").asText());
    var context =
        json.readTree(get(hr, "/api/v1/resignations/" + r.id() + "/employment-end").body());
    assertTrue(context.get("replaced").asBoolean());
    assertEquals("EFFECTIVE", context.get("phase").asText());
    assertEquals(
        replacement.lastWorkingDate().plusDays(1).toString(), context.get("inactiveDate").asText());
    assertEquals(
        0,
        db.queryForObject(
            "select count(*) from spring_session where principal_name in('sophia','marcus')",
            Integer.class));
    assertEquals(
        2,
        db.queryForObject(
            "select count(*) from hr_employment_end_handoff_current where employee_id='E003' and status='PENDING'",
            Integer.class));
  }
}
