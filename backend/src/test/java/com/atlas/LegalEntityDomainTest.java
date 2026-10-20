package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import org.junit.jupiter.api.Test;

class LegalEntityDomainTest {
  LocalDate today = LocalDate.of(2026, 10, 19);

  @Test
  void declaredValuesValidateAndNormaliseWithoutInventingCountryCurrencyCompatibility() {
    var e = new LegalEntity("SYNTH-GB", " Synthetic 李 Ltd ", "gb", "usd", "Europe/London", today);
    assertEquals("Synthetic 李 Ltd", e.name());
    assertDoesNotThrow(() -> e.validateNew(today));
  }

  @Test
  void rejectsControlsShapeAndMissingFields() {
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new LegalEntity("bad", "Name\n", "GB", "USD", "UTC", today));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new LegalEntity("GB1", "x".repeat(121), "GB", "USD", "UTC", today));
    assertThrows(
        ChangeRequest.Validation.class,
        () -> new LegalEntity("GB1", "Name", "GB", "USD", "UTC", null));
  }

  @Test
  void separatesHistoricalShapeFromNewReferenceAndDateValidation() {
    var e = new LegalEntity("OLD", "Historical", "ZZ", "ZZZ", "Old/Region", today.minusDays(1));
    assertEquals("ZZ", e.countryCode());
    assertThrows(ChangeRequest.Validation.class, () -> e.validateNew(today));
    assertThrows(
        ChangeRequest.Validation.class,
        () ->
            new LegalEntity("NEW", "Name", "GB", "GBP", "UTC", today.plusYears(1).plusDays(1))
                .validateNew(today));
  }
}
