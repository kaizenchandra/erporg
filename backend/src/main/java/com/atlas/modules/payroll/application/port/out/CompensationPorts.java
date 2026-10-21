package com.atlas.modules.payroll.application.port.out;

import com.atlas.modules.payroll.api.CompensationAccess.Salary;
import com.atlas.modules.payroll.application.model.CompensationModels.*;
import java.time.*;
import java.util.*;

public final class CompensationPorts {
  private CompensationPorts() {}

  public record Identity(String subject, String tenant, String employeeId, String role) {}

  public record Worker(String id, String status, LocalDate start) {}

  public interface Environment {
    Identity self(String subject);

    Worker worker(String subject, String employee);

    boolean payroll(String subject, String entity);
  }

  public interface Store {
    void lock(String tenant, String employee);

    boolean frozenAfter(String tenant, String employee, LocalDate episode, LocalDate date);

    Optional<Salary> sourceSalary(String tenant, String employee, String source);

    Optional<Setup> setup(String tenant, String id);

    Optional<Setup> replay(String tenant, String actor, String key);

    void setup(String tenant, Setup setup, String correlation);

    void decision(String tenant, Setup setup, String key, String correlation);

    List<Setup> setups(String tenant, String employee);

    List<Salary> salaries(String tenant, String employee);

    Optional<Salary> salary(String tenant, String employee, LocalDate date);

    void salary(
        String tenant,
        Salary salary,
        String actor,
        String reason,
        String correlation,
        BigDecimalBonus bonus);

    record BigDecimalBonus(java.math.BigDecimal amount) {}

    List<Bonus> bonuses(String tenant, String employee, LocalDate episode, LocalDate through);

    Optional<Calculation> calculation(String tenant, String runId);

    void capture(String tenant, Calculation calculation);

    boolean released(String tenant, String runId, String employee);
  }
}
