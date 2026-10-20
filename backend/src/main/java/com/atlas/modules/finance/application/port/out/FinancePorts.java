package com.atlas.modules.finance.application.port.out;

import com.atlas.modules.finance.application.model.FinanceModels.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

public final class FinancePorts {
  private FinancePorts() {}

  public interface Transactions {
    <T> T run(Supplier<T> work);
  }

  public interface References {
    Identity self(String subject);

    Reference entity(String subject, String code);
  }

  public interface Store {
    List<Grant> grants(Identity a);

    void lock(String tenant, String entity);

    Optional<Ledger> ledger(String tenant, String entity);

    void insertLedger(String tenant, Ledger l);

    Optional<Journal> journal(String tenant, String id);

    Optional<Journal> source(String tenant, String entity, String type, String id);

    void save(String tenant, Journal j);

    Page<Journal> journals(String tenant, String entity, String status, int page);

    Optional<Period> period(String tenant, String entity, LocalDate month);

    void insertPeriod(String tenant, String entity, Period p);

    Optional<Receipt> receipt(String tenant, String subject, String key);

    void receipt(String tenant, String subject, String key, String fingerprint, Object result);

    <T> T decode(String value, Class<T> type);

    void audit(
        Identity a,
        String entity,
        String id,
        String action,
        String reason,
        Object before,
        Object after,
        java.time.Instant now,
        String correlation);

    Page<Audit> history(String tenant, String entity, int page);

    List<Balance> balances(String tenant, String entity, LocalDate from, LocalDate through);
  }
}
