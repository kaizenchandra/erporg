package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.domain.model.EmploymentEnd;
import com.atlas.modules.corehr.domain.model.EndWithdrawal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EmploymentEndStore {
  Optional<EmploymentEnd> current(String tenant, String requestId);

  boolean replacementReviewOpen(String tenant, String requestId);

  Optional<EmploymentEnd> get(String tenant, String requestId);

  Optional<EmploymentEnd> duplicate(String tenant, String actor, String key);

  long openTasks(String tenant, String requestId, String employeeId);

  long activeReports(String tenant, String managerId, LocalDate asOf);

  void insert(String tenant, EmploymentEnd end, String key);

  List<EndWithdrawal> withdrawals(String tenant, String requestId);

  Optional<EndWithdrawal> withdrawalDuplicate(
      String tenant, String actor, String key, boolean review);

  void submitWithdrawal(String tenant, EndWithdrawal withdrawal, String key);

  void reviewWithdrawal(String tenant, EndWithdrawal withdrawal, String key);
}
