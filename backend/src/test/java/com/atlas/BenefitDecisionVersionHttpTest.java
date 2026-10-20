package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.benefits.application.model.*;
import com.atlas.modules.benefits.application.port.in.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class BenefitDecisionVersionHttpTest {
  static final String SCHEMA =
      "atlas_bh_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
  }

  @Autowired BenefitsUseCases benefits;
  @Autowired SelectionsUseCases selections;
  @Autowired JdbcTemplate db;
  @LocalServerPort int port;
  final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();

  HttpResponse<String> get(HttpClient c, String path) throws Exception {
    return c.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(HttpClient c, String path, String body, String type) throws Exception {
    var csrf = json.readTree(get(c, "/api/csrf").body());
    return c.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(csrf.get("headerName").asText(), csrf.get("token").asText())
            .header("Content-Type", type)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpClient login(String user) throws Exception {
    var c =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    assertEquals(
        204,
        post(
                c,
                "/api/login",
                "username=" + user + "&password=atlas-demo",
                "application/x-www-form-urlencoded")
            .statusCode());
    return c;
  }

  String fixture() {
    var code = "BH-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    benefits.create(
        "amelia",
        new CreatePlan(
            code,
            code,
            "GBP",
            "15.50",
            "0",
            LocalDate.now(ZoneOffset.UTC),
            "Synthetic exact-version fixture",
            UUID.randomUUID().toString()),
        "bh-plan");
    return selections
        .submit(
            "sophia",
            new SubmitSelection(
                code, List.of(), "Synthetic exact-version selection", UUID.randomUUID().toString()),
            "bh-submit")
        .id();
  }

  HttpResponse<String> decide(HttpClient c, String id, String version, String outcome, String key)
      throws Exception {
    return post(
        c,
        "/api/v1/benefits/selections/" + id + "/decision",
        "{"
            + (version == null ? "" : "\"version\":" + version + ",")
            + "\"outcome\":\""
            + outcome
            + "\",\"reason\":\"Synthetic exact version review\",\"idempotencyKey\":\""
            + key
            + "\"}",
        "application/json");
  }

  void assertState(String id, String status, long version, int audits, int handoffs) {
    assertEquals(
        status,
        db.queryForObject("select status from benefit_selection where id=?", String.class, id));
    assertEquals(
        version,
        db.queryForObject("select version from benefit_selection where id=?", Long.class, id));
    assertEquals(
        audits,
        db.queryForObject(
            "select count(*) from benefit_selection_audit where selection_id=?",
            Integer.class,
            id));
    assertEquals(
        handoffs,
        db.queryForObject(
            "select count(*) from benefit_deduction_handoff where selection_id=?",
            Integer.class,
            id));
  }

  @Test
  void fractionalPendingVersionMustNotApprove() throws Exception {
    var id = fixture();
    var hr = login("amelia");
    assertEquals(400, decide(hr, id, "0.5", "APPROVED", UUID.randomUUID().toString()).statusCode());
    assertState(id, "PENDING", 0, 1, 0);
  }

  @Test
  void exactBoundsReplayRejectionAndAccessRemainAuthoritative() throws Exception {
    var id = fixture();
    var hr = login("amelia");
    for (String value :
        Arrays.asList(
            null,
            "null",
            "-1",
            "-0.5",
            "0.99999999999999999999",
            "1e-100",
            "9223372036854775808",
            "1e40")) {
      var result = decide(hr, id, value, "APPROVED", UUID.randomUUID().toString());
      assertEquals(400, result.statusCode(), "version=" + value);
      assertTrue(json.readTree(result.body()).toString().contains("version"));
      assertState(id, "PENDING", 0, 1, 0);
    }
    assertEquals(
        409,
        decide(hr, id, "9223372036854775807", "APPROVED", UUID.randomUUID().toString())
            .statusCode());
    assertEquals(
        403,
        decide(login("sophia"), id, "0", "APPROVED", UUID.randomUUID().toString()).statusCode());
    assertEquals(
        403,
        decide(login("outsider"), id, "0", "APPROVED", UUID.randomUUID().toString()).statusCode());
    assertState(id, "PENDING", 0, 1, 0);
    var key = UUID.randomUUID().toString();
    assertEquals(200, decide(hr, id, "0.000", "APPROVED", key).statusCode());
    assertEquals(200, decide(hr, id, "0e5", "APPROVED", key).statusCode());
    assertEquals(400, decide(hr, id, "0.5", "APPROVED", key).statusCode());
    assertEquals(409, decide(hr, id, "1", "APPROVED", key).statusCode());
    assertState(id, "APPROVED", 1, 2, 1);
    var rejected = fixture();
    assertEquals(
        200, decide(hr, rejected, "0", "REJECTED", UUID.randomUUID().toString()).statusCode());
    assertState(rejected, "REJECTED", 1, 2, 0);
  }
}
