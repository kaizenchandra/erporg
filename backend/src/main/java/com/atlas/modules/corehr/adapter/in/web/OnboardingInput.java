package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.CompleteOnboarding;
import java.math.BigDecimal;

public record OnboardingInput(String reason, BigDecimal version, String idempotencyKey) {
  CompleteOnboarding command() {
    return new CompleteOnboarding(reason, CoreHrCommandVersion.exact(version), idempotencyKey);
  }
}
