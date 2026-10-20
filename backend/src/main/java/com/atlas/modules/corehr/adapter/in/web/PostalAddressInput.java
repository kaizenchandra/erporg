package com.atlas.modules.corehr.adapter.in.web;

import com.atlas.modules.corehr.application.model.PostalAddressModels;
import java.math.BigDecimal;
import java.time.LocalDate;

public record PostalAddressInput(
    PostalAddressModels.Input address,
    LocalDate effectiveDate,
    String reason,
    BigDecimal version,
    String idempotencyKey) {
  PostalAddressModels.Submit command() {
    return new PostalAddressModels.Submit(
        address, effectiveDate, reason, CoreHrCommandVersion.exact(version), idempotencyKey);
  }
}
