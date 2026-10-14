package com.atlas.configuration;

import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@Configuration
public class SecurityConfiguration {
  private HttpSecurity base(HttpSecurity http) throws Exception {
    return http.authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/csrf",
                        "/api/auth-mode",
                        "/api/login",
                        "/oauth2/**",
                        "/login/**",
                        "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .exceptionHandling(e -> e.authenticationEntryPoint((q, s, x) -> s.sendError(401)))
        .logout(
            l ->
                l.logoutUrl("/api/logout")
                    .logoutSuccessHandler((q, s, a) -> s.setStatus(204))
                    .deleteCookies("JSESSIONID"))
        .headers(
            h -> h.cacheControl(c -> {}).contentTypeOptions(c -> {}).frameOptions(f -> f.deny()));
  }

  @Bean
  @Profile("local")
  SecurityFilterChain local(HttpSecurity http) throws Exception {
    return base(http)
        .formLogin(
            f ->
                f.loginProcessingUrl("/api/login")
                    .successHandler((q, s, a) -> s.setStatus(204))
                    .failureHandler((q, s, e) -> s.sendError(401)))
        .build();
  }

  @Bean
  @Profile("local")
  UserDetailsService users() {
    var encoder = new BCryptPasswordEncoder();
    String password = System.getenv().getOrDefault("LOCAL_DEMO_PASSWORD", "atlas-demo");
    return new InMemoryUserDetailsManager(
        java.util.stream.Stream.of("amelia", "marcus", "sophia", "outsider")
            .map(
                n ->
                    User.withUsername(n)
                        .password("{bcrypt}" + encoder.encode(password))
                        .roles("USER")
                        .build())
            .toList());
  }

  @Bean
  @Profile("!local")
  SecurityFilterChain oidc(
      HttpSecurity http,
      org.springframework.security.oauth2.client.registration.ClientRegistrationRepository clients)
      throws Exception {
    var logout =
        new org.springframework.security.oauth2.client.oidc.web.logout
            .OidcClientInitiatedLogoutSuccessHandler(clients);
    logout.setPostLogoutRedirectUri(
        System.getenv().getOrDefault("APP_ORIGIN", "http://127.0.0.1:3000") + "/");
    return base(http)
        .oauth2Login(
            o ->
                o.defaultSuccessUrl(
                    System.getenv().getOrDefault("APP_ORIGIN", "http://127.0.0.1:3000"), true))
        .logout(l -> l.logoutSuccessHandler(logout))
        .build();
  }

  @RestController
  static class CsrfController {
    private final org.springframework.core.env.Environment env;

    CsrfController(org.springframework.core.env.Environment env) {
      this.env = env;
    }

    @GetMapping("/api/csrf")
    Map<String, String> csrf(CsrfToken token) {
      return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/api/auth-mode")
    Map<String, String> mode() {
      return Map.of("mode", env.matchesProfiles("local") ? "local" : "oidc");
    }
  }
}
