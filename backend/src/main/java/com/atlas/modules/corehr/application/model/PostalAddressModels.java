package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.PostalAddress;
import java.time.LocalDate;
import java.util.List;

public final class PostalAddressModels {
  private PostalAddressModels() {}

  public record Input(
      String line1,
      String line2,
      String locality,
      String region,
      String postalCode,
      String countryCode) {}

  public record Submit(
      Input address, LocalDate effectiveDate, String reason, Long version, String idempotencyKey) {}

  public record Country(String code, String name) {}

  public record Context(
      String employeeId,
      long employeeVersion,
      String legacyAddress,
      PostalAddress address,
      List<Country> countries) {}
}
