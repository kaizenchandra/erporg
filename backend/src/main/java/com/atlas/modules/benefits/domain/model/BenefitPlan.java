package com.atlas.modules.benefits.domain.model;

import com.atlas.shared.domain.InputValidation;
import java.math.*;
import java.time.*;
import java.util.*;

public record BenefitPlan(
    String code,
    String name,
    String currency,
    BigDecimal monthlyContribution,
    int minimumServiceDays,
    LocalDate effectiveFrom,
    String ruleVersion) {
  public static final String RULE_VERSION = "DEMO-FIXED-MONTHLY-v1";

  public BenefitPlan(
      String code,
      String name,
      String currency,
      BigDecimal monthlyContribution,
      int minimumServiceDays,
      LocalDate effectiveFrom) {
    this(
        code, name, currency, monthlyContribution, minimumServiceDays, effectiveFrom, RULE_VERSION);
  }

  public BenefitPlan {
    var errors = new LinkedHashMap<String, String>();
    if (!RULE_VERSION.equals(ruleVersion))
      errors.put("ruleVersion", "Unsupported demonstration rule version.");
    if (code == null || !code.matches("[A-Z0-9-]{2,30}"))
      errors.put("code", "Use 2–30 uppercase letters, digits or hyphens.");
    if (name == null
        || name.isBlank()
        || name.length() > 120
        || name.codePoints().anyMatch(Character::isISOControl))
      errors.put("name", "Enter a plan name of 1–120 characters without control characters.");
    if (currency == null || !Set.of("USD", "GBP", "EUR").contains(currency))
      errors.put("currency", "Choose a demonstration currency: USD, GBP or EUR.");
    if (monthlyContribution == null
        || monthlyContribution.signum() < 0
        || monthlyContribution.compareTo(new BigDecimal("999999.99")) > 0)
      errors.put("monthlyContribution", "Enter a monthly contribution from 0 to 999999.99.");
    else
      try {
        monthlyContribution = monthlyContribution.setScale(2, RoundingMode.UNNECESSARY);
      } catch (ArithmeticException e) {
        errors.put("monthlyContribution", "Use at most two decimal places; no implicit rounding.");
      }
    if (minimumServiceDays < 0 || minimumServiceDays > 3650)
      errors.put("minimumServiceDays", "Use 0–3650 service days.");
    if (effectiveFrom == null) errors.put("effectiveFrom", "Plan start date is required.");
    if (!errors.isEmpty()) throw new InputValidation(errors);
    name = name.trim();
  }

  public Eligibility eligibility(String status, LocalDate employmentStart, LocalDate today) {
    if (effectiveFrom.isAfter(today))
      return new Eligibility(false, "Plan starts on " + effectiveFrom + ".");
    if (!Set.of("Active", "On leave").contains(status))
      return new Eligibility(
          false, "Demonstration eligibility requires Active or On leave employment.");
    if (employmentStart.plusDays(minimumServiceDays).isAfter(today))
      return new Eligibility(
          false,
          "Minimum service is reached on " + employmentStart.plusDays(minimumServiceDays) + ".");
    return new Eligibility(true, "Meets configured demonstration employment and service rules.");
  }

  public record Eligibility(boolean eligible, String explanation) {}
}
