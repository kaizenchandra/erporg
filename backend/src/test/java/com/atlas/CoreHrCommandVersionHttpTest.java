package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.application.model.*;
import com.atlas.modules.corehr.application.model.Commands.Decide;
import com.atlas.modules.corehr.application.port.in.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CoreHrCommandVersionHttpTest {
  static final String SCHEMA =
      "atlas_bk_http_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add(
        "spring.datasource.url",
        () -> "jdbc:postgresql://localhost:5438/atlas?currentSchema=" + SCHEMA);
    r.add("spring.flyway.default-schema", () -> SCHEMA);
    r.add("spring.flyway.schemas", () -> SCHEMA);
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
  }

  @Autowired PeopleUseCases people;
  @Autowired HiringUseCases hiring;
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

  Map<String, String> state() {
    var result = new TreeMap<String, String>();
    for (var table :
        List.of(
            "hr_employee",
            "hr_request",
            "hr_version",
            "hr_audit",
            "hr_notification",
            "hr_employee_event",
            "hr_onboarding_completion")) {
      // Closed test-owned table list; compare complete row contents, not only counts.
      result.put(
          table,
          db.queryForObject(
              "select md5(coalesce(string_agg(row_to_json(t)::text, '' order by row_to_json(t)::text),'')) from "
                  + table
                  + " t",
              String.class));
    }
    return result;
  }

  String body(String data, String version, String key) {
    return "{"
        + data
        + (version == null ? "" : ",\"version\":" + version)
        + ",\"reason\":\"Synthetic exact revision\",\"idempotencyKey\":\""
        + key
        + "\"}";
  }

  void invalids(HttpClient client, String endpoint, String data) throws Exception {
    var before = state();
    for (String version :
        Arrays.asList(
            "0.5",
            null,
            "null",
            "-1",
            "-0.5",
            "0.99999999999999999999",
            "1e-100",
            "9223372036854775808",
            "1e40")) {
      var response =
          post(
              client,
              endpoint,
              body(data, version, UUID.randomUUID().toString()),
              "application/json");
      assertEquals(400, response.statusCode(), "version=" + version);
      assertTrue(
          json.readTree(response.body()).path("fieldErrors").has("version"), response.body());
      assertEquals(before, state(), "Rejected version must not change business state: " + version);
    }
  }

  @Test
  void employeeChangeRejectsFractionsWithoutMutationThenAcceptsExactReplay() throws Exception {
    var client = login("sophia");
    var data =
        "\"employeeId\":\"E003\",\"field\":\"phone\",\"newValue\":\"+1 202 555 9898\",\"effectiveDate\":\""
            + LocalDate.now(ZoneOffset.UTC)
            + "\"";
    invalids(client, "/api/v1/requests", data);
    var key = UUID.randomUUID().toString();
    var valid = post(client, "/api/v1/requests", body(data, "0.000", key), "application/json");
    assertEquals(200, valid.statusCode(), valid.body());
    var after = state();
    assertEquals(
        valid.body(),
        post(client, "/api/v1/requests", body(data, "0e5", key), "application/json").body());
    assertEquals(after, state());
    assertEquals(
        409,
        post(
                client,
                "/api/v1/requests",
                body(data, "1", UUID.randomUUID().toString()),
                "application/json")
            .statusCode());
    assertEquals(after, state());
  }

  @Test
  void postalAddressRejectsFractionsWithoutMutationThenAcceptsExactReplay() throws Exception {
    var client = login("sophia");
    var endpoint = "/api/v1/employees/E003/postal-address";
    var data =
        "\"address\":{\"line1\":\"18 Synthetic Road\",\"line2\":\"\",\"locality\":\"London\",\"region\":\"\",\"postalCode\":\"SW1A 1AA\",\"countryCode\":\"GB\"},\"effectiveDate\":\""
            + LocalDate.now(ZoneOffset.UTC)
            + "\"";
    invalids(client, endpoint, data);
    var key = UUID.randomUUID().toString();
    var valid = post(client, endpoint, body(data, "0.0", key), "application/json");
    assertEquals(200, valid.statusCode(), valid.body());
    var after = state();
    assertEquals(
        valid.body(), post(client, endpoint, body(data, "0e2", key), "application/json").body());
    assertEquals(
        403,
        post(
                login("marcus"),
                endpoint,
                body(data, "0", UUID.randomUUID().toString()),
                "application/json")
            .statusCode());
    assertEquals(
        403,
        post(
                login("outsider"),
                endpoint,
                body(data, "0", UUID.randomUUID().toString()),
                "application/json")
            .statusCode());
    assertEquals(after, state());
  }

  @Test
  void onboardingRejectsFractionsWithoutMutationThenCompletesExactlyOnce() throws Exception {
    var submitted =
        hiring.submit(
            people.actor("amelia"),
            new SubmitHire(
                "BK Synthetic",
                UUID.randomUUID() + "@example.test",
                "+1 202 555 9898",
                "Test Analyst",
                "Design",
                "London, UK",
                "E002",
                LocalDate.now(ZoneOffset.UTC),
                "Synthetic version fixture",
                UUID.randomUUID().toString()),
            "bk-hire");
    var approved =
        hiring.decide(
            people.actor("olivia"),
            submitted.id(),
            new Decide("APPROVED", "Independent fixture review"),
            "bk-hire-review");
    var version = people.profile(people.actor("amelia"), approved.employeeId()).version();
    var client = login("amelia");
    var endpoint = "/api/v1/hiring/requests/" + approved.id() + "/complete-onboarding";
    var data = "\"fixture\":\"BK\"";
    invalids(client, endpoint, data);
    var before = state();
    assertEquals(
        403,
        post(
                login("sophia"),
                endpoint,
                body(data, "" + version, UUID.randomUUID().toString()),
                "application/json")
            .statusCode());
    assertEquals(
        403,
        post(
                login("outsider"),
                endpoint,
                body(data, "" + version, UUID.randomUUID().toString()),
                "application/json")
            .statusCode());
    assertEquals(before, state());
    var key = UUID.randomUUID().toString();
    var valid = post(client, endpoint, body(data, version + ".000", key), "application/json");
    assertEquals(200, valid.statusCode(), valid.body());
    var after = state();
    assertEquals(
        valid.body(),
        post(client, endpoint, body(data, version + "e0", key), "application/json").body());
    assertEquals(after, state());
    assertEquals("Active", people.profile(people.actor("amelia"), approved.employeeId()).status());
  }
}
