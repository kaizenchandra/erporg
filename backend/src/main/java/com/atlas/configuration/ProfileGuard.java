package com.atlas.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
public class ProfileGuard {
  public ProfileGuard(Environment environment) {
    if (environment.matchesProfiles("prod")
        && environment.matchesProfiles("local | oidc-demo | acceptance"))
      throw new IllegalStateException(
          "Production cannot enable synthetic identity or seed profiles.");
  }
}
