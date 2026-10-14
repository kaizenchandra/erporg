package com.atlas.configuration;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestGuard extends OncePerRequestFilter {
  private record Window(long minute, AtomicInteger count) {}

  private final int loginLimit;
  private final int apiLimit;
  private final Clock clock;

  @Autowired
  public RequestGuard(
      @Value("${atlas.security.login-per-minute:30}") int loginLimit,
      @Value("${atlas.security.requests-per-minute:600}") int apiLimit) {
    this(loginLimit, apiLimit, Clock.systemUTC());
  }

  public RequestGuard(int loginLimit, int apiLimit, Clock clock) {
    if (loginLimit < 1 || loginLimit > 600 || apiLimit < 1 || apiLimit > 100000)
      throw new IllegalArgumentException("Request limits must be bounded positive values.");
    this.loginLimit = loginLimit;
    this.apiLimit = apiLimit;
    this.clock = clock;
  }

  private final ConcurrentHashMap<String, Window> limits = new ConcurrentHashMap<>();

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    String correlation = UUID.randomUUID().toString();
    request.setAttribute("correlationId", correlation);
    response.setHeader("X-Correlation-ID", correlation);
    long start = System.nanoTime();
    long minute = clock.millis() / 60000;
    boolean login = request.getRequestURI().equals("/api/login");
    String key = request.getRemoteAddr() + (login ? ":login" : ":api");
    if (limits.size() > 10000) limits.entrySet().removeIf(e -> e.getValue().minute() < minute);
    if (limits.size() > 10000 && !limits.containsKey(key)) {
      response.setStatus(429);
      return;
    }
    Window window =
        limits.compute(
            key,
            (k, w) ->
                w == null || w.minute() != minute ? new Window(minute, new AtomicInteger()) : w);
    if (window.count().incrementAndGet() > (login ? loginLimit : apiLimit)) {
      response.setHeader("Retry-After", "60");
      response.setStatus(429);
      return;
    }
    if (request.getContentLengthLong() > 32768) {
      response.setStatus(413);
      return;
    }
    HttpServletRequest bounded = request;
    if (request.getContentType() != null
        && request.getContentType().startsWith("application/json")) {
      byte[] body = request.getInputStream().readNBytes(32769);
      if (body.length > 32768) {
        response.setStatus(413);
        return;
      }
      bounded =
          new HttpServletRequestWrapper(request) {
            public ServletInputStream getInputStream() {
              var input = new ByteArrayInputStream(body);
              return new ServletInputStream() {
                public int read() {
                  return input.read();
                }

                public boolean isFinished() {
                  return input.available() == 0;
                }

                public boolean isReady() {
                  return true;
                }

                public void setReadListener(ReadListener listener) {
                  throw new UnsupportedOperationException("Async request bodies are not supported");
                }
              };
            }
          };
    }
    try {
      chain.doFilter(bounded, response);
    } finally {
      LoggerFactory.getLogger(RequestGuard.class)
          .info(
              "request_completed correlation={} status={} duration_ms={}",
              correlation,
              response.getStatus(),
              (System.nanoTime() - start) / 1000000);
    }
  }
}
