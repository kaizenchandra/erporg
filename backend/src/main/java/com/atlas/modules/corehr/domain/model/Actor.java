package com.atlas.modules.corehr.domain.model;

public record Actor(String subject, String tenant, String employeeId, String name, String role) {
  public Actor {
    if (subject == null
        || subject.isBlank()
        || tenant == null
        || tenant.isBlank()
        || employeeId == null
        || employeeId.isBlank()
        || role == null
        || !java.util.Set.of("HR", "MANAGER", "EMPLOYEE").contains(role)) {
      throw new ChangeRequest.Denied();
    }
  }

  public boolean hr() {
    return role.equals("HR");
  }

  public boolean canRead(Employee employee) {
    return hr()
        || employeeId.equals(employee.id())
        || (role.equals("MANAGER") && employeeId.equals(employee.managerId()));
  }

  public boolean canChange(Employee employee, String field) {
    return canRead(employee)
        && (field.equals("phone")
            || hr()
            || (role.equals("MANAGER") && !employeeId.equals(employee.id())));
  }
}
