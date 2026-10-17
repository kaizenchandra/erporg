package com.atlas.configuration;

import java.time.Clock;
import org.springframework.context.annotation.*;

@Configuration
public class BusinessClockConfiguration {
  @Bean
  Clock businessClock() {
    return Clock.systemUTC();
  }
}
