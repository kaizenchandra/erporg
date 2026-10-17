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
@Import(EndWithdrawalAccessHttpTest.TimeConfiguration.class)
class EndWithdrawalAccessHttpTest {
  static final String SCHEMA =
      "atlas_withdraw_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

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

  String schedule(String user, String employee) {
    var e = people.profile(people.actor(user), employee);
    var r =
        resignations.submit(
            user,
            new ResignationCommands.Submit(
                LocalDate.now(clock).plusDays(5),
                "HTTP withdrawal boundary",
                BigDecimal.valueOf(e.version()),
                UUID.randomUUID().toString()),
            "submitted");
    resignations.review(
        "amelia",
        r.id(),
        new ResignationCommands.Review(
            "APPROVED", "Independent", BigDecimal.ZERO, UUID.randomUUID().toString()),
        "approved");
    ends.confirm(
        "amelia",
        r.id(),
        new Confirm("Schedule end", BigDecimal.valueOf(e.version()), UUID.randomUUID().toString()),
        "scheduled");
    return r.id();
  }

  Confirm command(String id) {
    return new Confirm(
        "Continue employment",
        BigDecimal.valueOf(ends.context("amelia", id).employeeVersion()),
        UUID.randomUUID().toString());
  }

  @Test
  void
      approvedWithdrawalKeepsSessionsAndNewSignInAtOriginalCutoffButPendingRequestCannotReactivate()
          throws Exception {
    db.update(
        "update identity_membership set employee_id='E003',role='EMPLOYEE' where subject='marcus'");
    var session = client();
    var alias = client();
    var hr = client();
    assertEquals(204, login(session, "sophia"));
    assertEquals(204, login(alias, "marcus"));
    assertEquals(204, login(hr, "amelia"));
    var id = schedule("sophia", "E003");
    var w = ends.withdraw("amelia", id, command(id), "requested");
    var v = BigDecimal.valueOf(ends.context("amelia", id).employeeVersion());
    ends.reviewWithdrawal(
        "olivia",
        id,
        w.id(),
        new ReviewWithdrawal(
            "APPROVED", "Independent continuation", v, UUID.randomUUID().toString()),
        "reviewed");
    var pending = schedule("olivia", "E005");
    var p = ends.withdraw("amelia", pending, command(pending), "pending");
    var cutoff = ends.context("amelia", id).inactiveDate().atStartOfDay(ZoneOffset.UTC).toInstant();
    clock.time = cutoff;
    for (var c : List.of(session, alias)) assertEquals(200, get(c, "/api/v1/session").statusCode());
    assertEquals(204, login(client(), "sophia"));
    assertEquals(204, login(client(), "marcus"));
    assertEquals(
        "Active", json.readTree(get(hr, "/api/v1/employees/E003").body()).get("status").asText());
    assertEquals(
        "WITHDRAWN",
        json.readTree(get(hr, "/api/v1/resignations/" + id + "/employment-end").body())
            .get("phase")
            .asText());
    assertEquals(401, login(client(), "olivia"));
    assertEquals(
        "Ended", json.readTree(get(hr, "/api/v1/employees/E005").body()).get("status").asText());
    // Rebind a distinct configured HR identity for review; no client role headers.
    db.update("update identity_membership set employee_id='E002',role='HR' where subject='marcus'");
    assertThrows(
        com.atlas.modules.corehr.domain.model.ChangeRequest.Conflict.class,
        () ->
            ends.reviewWithdrawal(
                "marcus",
                pending,
                p.id(),
                new ReviewWithdrawal(
                    "APPROVED",
                    "Too late",
                    BigDecimal.valueOf(ends.context("amelia", pending).employeeVersion()),
                    UUID.randomUUID().toString()),
                "late"));
    assertEquals(401, login(client(), "olivia"));
  }
}
