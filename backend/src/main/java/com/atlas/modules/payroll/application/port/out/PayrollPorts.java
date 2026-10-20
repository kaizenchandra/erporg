package com.atlas.modules.payroll.application.port.out;

import com.atlas.modules.payroll.application.model.PayrollModels.*;
import java.util.*;
import java.util.function.Supplier;

public final class PayrollPorts {
  private PayrollPorts() {}

  public interface Transactions {
    <T> T run(Supplier<T> work);
  }

  public interface Environment {
    Identity self(String subject);

    List<Grant> grants(String subject);

    Ledger ledger(String subject, String entity);

    Worker worker(String subject, String id);

    Source source(String subject, String id);

    String journal(String subject, Run run, String reason, String key, String correlation);
  }

  public interface Store {
    void lock(String tenant, String key);

    Optional<Run> run(String tenant, String id);

    Optional<Run> source(String tenant, String id);

    void save(String tenant, Run r);

    Page runs(String tenant, String entity, String employee, boolean all, int page);

    Optional<Receipt> receipt(String tenant, String subject, String key);

    void receipt(Identity a, String key, String fp, Run run);

    Run decode(String json);

    void audit(
        Identity a,
        String id,
        String action,
        String reason,
        Object before,
        Object after,
        java.time.Instant now,
        String correlation);

    List<Audit> history(String tenant, String id);
  }
}
