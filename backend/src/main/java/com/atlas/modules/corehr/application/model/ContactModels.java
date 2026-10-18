package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

public final class ContactModels {
  private ContactModels() {}

  public record Input(String name, String relationship, String phone) {}

  public record Submit(
      List<Input> contacts,
      LocalDate effectiveDate,
      String reason,
      BigDecimal employeeVersion,
      String idempotencyKey) {}

  public record Review(
      String decision, String comment, BigDecimal version, String idempotencyKey) {}

  public record Query(String status, int page, int size) {
    public Query {
      if (status == null
          || !Set.of("ALL", "SUBMITTED", "APPROVED", "REJECTED").contains(status)
          || page < 0
          || page > 100000
          || size < 1
          || size > 50)
        throw new IllegalArgumentException("Use a supported status, page 0–100000 and size 1–50.");
    }
  }

  public record Page(List<ContactRequest> items, long total, int page, int size) {}

  public record Context(
      String employeeId,
      long employeeVersion,
      List<EmergencyContact> contacts,
      LocalDate effectiveDate,
      Page requests) {}
}
