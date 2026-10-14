package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.configuration.RequestGuard;
import java.time.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RequestGuardTest {
  Clock clock = Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void defaultLoginBudgetRejectsThirtyFirstRequestAndDoesNotTrustForwardedIp() throws Exception {
    var guard = new RequestGuard(30, 600, clock);
    var calls = new AtomicInteger();
    for (int i = 0; i < 31; i++) {
      var request = new MockHttpServletRequest("POST", "/api/login");
      request.setRemoteAddr("127.0.0.1");
      request.addHeader("X-Forwarded-For", "198.51.100." + i);
      var response = new MockHttpServletResponse();
      guard.doFilter(
          request,
          response,
          (q, r) -> {
            calls.incrementAndGet();
            ((jakarta.servlet.http.HttpServletResponse) r).setStatus(204);
          });
      assertEquals(i < 30 ? 204 : 429, response.getStatus());
      assertNotNull(response.getHeader("X-Correlation-ID"));
      if (i == 30) assertEquals("60", response.getHeader("Retry-After"));
    }
    assertEquals(30, calls.get());
  }

  @Test
  void acceptanceProfileCannotBeCombinedWithProduction() {
    var environment = new org.springframework.mock.env.MockEnvironment();
    environment.setActiveProfiles("prod", "acceptance");
    assertThrows(
        IllegalStateException.class, () -> new com.atlas.configuration.ProfileGuard(environment));
  }

  @Test
  void oversizedJsonCannotReachApplication() throws Exception {
    var guard = new RequestGuard(30, 600, clock);
    var calls = new AtomicInteger();
    var request = new MockHttpServletRequest("POST", "/api/v1/hiring/requests");
    request.setContentType("application/json");
    request.setContent(new byte[32769]);
    var response = new MockHttpServletResponse();
    guard.doFilter(request, response, (q, r) -> calls.incrementAndGet());
    assertEquals(413, response.getStatus());
    assertEquals(0, calls.get());
  }
}
