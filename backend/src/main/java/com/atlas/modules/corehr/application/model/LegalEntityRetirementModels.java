package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.LegalEntityRetirement.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class LegalEntityRetirementModels {
  public record InboxQuery(String scope, String status, int page, int size) {
    public InboxQuery {
      if (!java.util.Set.of("OWN", "REVIEW").contains(scope == null ? "" : scope)
          || !java.util.Set.of("PENDING", "APPROVED", "REJECTED", "ALL")
              .contains(status == null ? "" : status)
          || page < 0
          || page > 4000
          || size < 1
          || size > 50)
        throw new com.atlas.modules.corehr.domain.model.ChangeRequest.Validation(
            java.util.Map.of(
                "query", "Choose OWN/REVIEW, a supported status, page 0–4000 and size 1–50."));
    }
  }

  public record InboxScope(String tenant, String subject, String employeeId, boolean own) {}

  public record InboxPage(List<State> items, long total, int page, int size) {}

  public record NotificationMember(String subject, String tenant, String employeeId, String role) {}

  private LegalEntityRetirementModels() {}

  public record Submit(
      LocalDate effectiveDate, String reason, BigDecimal version, String idempotencyKey) {}

  public record Review(
      String decision, String comment, BigDecimal version, String idempotencyKey) {}

  public record State(Proposal request, Decision decision) {}

  public record Context(
      LegalEntityModels.Entry reference,
      long version,
      LocalDate retirementDate,
      boolean assignmentRecorded,
      List<State> requests) {}
}
