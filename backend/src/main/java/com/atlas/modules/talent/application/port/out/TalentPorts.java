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
    void lock(String tenant);

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

    String compensationIntake(String subject, Compensation plan, String reason, String correlation);
  }
}
