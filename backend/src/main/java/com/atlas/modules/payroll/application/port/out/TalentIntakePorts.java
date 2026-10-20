package com.atlas.modules.payroll.application.port.out;

import com.atlas.modules.payroll.api.TalentIntakeAccess.*;
import java.util.*;

public final class TalentIntakePorts {
  private TalentIntakePorts() {}

  public record Identity(String subject, String tenant, String employeeId, String role) {}

  public interface IdentityAccess {
    Identity self(String subject);

    void worker(String subject, String employee);
  }

  public interface Store {
    void lock(String tenant, String source);

    Optional<Intake> find(String tenant, String source);

    void insert(String tenant, Intake i, String reason, String correlation);

    List<Intake> pending(String tenant, int page);
  }
}
