package com.atlas.configuration;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.*;
import tools.jackson.databind.DeserializationFeature;

/** Do not silently truncate JSON fractions into ratings, headcounts or validity days. */
@Configuration
public class ExactIntegerTransportConfiguration {
  @Bean
  JsonMapperBuilderCustomizer exactIntegerTransport() {
    return builder -> builder.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
  }
}
