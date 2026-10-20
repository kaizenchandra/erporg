package com.atlas.modules.benefits.adapter.in.web;

import com.atlas.modules.benefits.application.model.DecideSelection;
import com.atlas.shared.domain.InputValidation;
import java.math.BigDecimal;
import java.util.Map;

/** Preserve the HTTP number until exact conversion; never let JSON coerce a fraction to Long. */
public record BenefitDecisionInput(
    BigDecimal version, String outcome, String reason, String idempotencyKey) {
  DecideSelection command() {
    try {
      if (version == null || version.signum() < 0) throw new ArithmeticException();
      return new DecideSelection(version.longValueExact(), outcome, reason, idempotencyKey);
    } catch (ArithmeticException e) {
      throw new InputValidation(
          Map.of("version", "An exact nonnegative selection version is required."));
    }
  }
}
