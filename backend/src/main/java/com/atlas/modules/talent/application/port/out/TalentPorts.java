package com.atlas.modules.talent.application.port.out;

import com.atlas.modules.talent.application.model.TalentCommands.*;
import com.atlas.modules.talent.domain.model.TalentModels.*;
import java.util.*;
import java.util.function.Supplier;

public final class TalentPorts {
  private TalentPorts() {}

  public interface Transactions {
    <T> T run(Supplier<T> work);
  }

  public interface Store {
    Optional<com.atlas.modules.talent.application.model.ReminderModels.Policy> reminderPolicy(
        String tenant);

    Optional<com.atlas.modules.talent.application.model.ReminderModels.State> reminderState(
        String tenant);

    void reminderPolicy(
        String tenant, com.atlas.modules.talent.application.model.ReminderModels.Policy policy);

    void reminderState(
        String tenant, com.atlas.modules.talent.application.model.ReminderModels.State state);

    Optional<com.atlas.modules.talent.application.model.ReminderModels.Due> reminderDue(
        java.time.Instant now);

    List<com.atlas.modules.talent.application.model.ReminderModels.Source> reminderSources(
        String tenant, java.time.Instant cutoff, java.time.LocalDate day);

    void reminderRun(
        String tenant, com.atlas.modules.talent.application.model.ReminderModels.Run run);

    void reminderItem(
        String tenant, com.atlas.modules.talent.application.model.ReminderModels.Item item);

    void reminderNotice(String tenant, Notice notice, java.time.LocalDate day, String runId);

    List<com.atlas.modules.talent.application.model.ReminderModels.Run> reminderRuns(
        String tenant, int page);

    List<com.atlas.modules.talent.application.model.ReminderModels.Item> reminderItems(
        String tenant, String runId);

    void lock(String tenant);

    List<ResponsibilityHistory> delegationHistory(
        String tenant, String kind, String recordId, int page);

    boolean delegationOverlap(
        String tenant,
        String kind,
        String recordId,
        long version,
        java.time.LocalDate from,
        java.time.LocalDate through);

    List<Pending> approvalCandidates(String tenant, int page);

    Optional<Delegation> delegationReplay(String tenant, String actor, String key);

    Optional<Delegation> delegation(String tenant, String id);

    Optional<Delegation> activeDelegation(
        String tenant, String kind, String recordId, long version, java.time.LocalDate date);

    void delegate(String tenant, Delegation delegation);

    Optional<DelegationRevocation> revocation(String tenant, String id);

    void revokeDelegation(String tenant, DelegationRevocation revocation);

    void notice(String tenant, Notice notice);

    List<Notice> notices(String tenant, String employee, int page);

    void exportReceipt(
        String tenant,
        String actor,
        String employee,
        String kind,
        int page,
        int rows,
        String correlation,
        java.time.Instant now);

    boolean enrolled(String tenant, String course, String employee);

    <T> Optional<T> find(String tenant, String kind, String id, Class<T> type);

    <T> Page<T> page(String tenant, String kind, String employee, int page, Class<T> type);

    void insert(String tenant, String kind, String id, Object snapshot);

    void update(String tenant, String kind, String id, long version, Object snapshot);

    Optional<Receipt> receipt(String tenant, String subject, String key);

    void receipt(String tenant, String subject, String key, String fingerprint, Object result);

    String encode(Object value);

    <T> T decode(String value, Class<T> type);

    void audit(String tenant, Audit audit);

    List<Audit> history(String tenant, String kind, String id, int page);
  }

  public interface Environment {
    Actor self(String subject);

    List<String> hrReviewers(String subject);

    boolean hasRole(String subject, String employee, String role);

    Worker worker(String subject, String employee);

    Workers workers(String subject, String search, int page);

    record HireInput(
        String name,
        String email,
        String phone,
        String title,
        String department,
        String location,
        String managerId,
        java.time.LocalDate start) {}

    record Hire(String id, String status, String employeeId) {}

    Hire submitHire(String subject, HireInput input, String reason, String key, String correlation);

    Hire decideHire(String subject, String id, String decision, String reason, String correlation);

    void completeOnboarding(
        String subject, String hireId, long version, String reason, String key, String correlation);

    java.math.BigDecimal currentSalary(
        String subject, String employee, java.time.LocalDate date, String currency);

    String compensationIntake(String subject, Compensation plan, String reason, String correlation);
  }
}
