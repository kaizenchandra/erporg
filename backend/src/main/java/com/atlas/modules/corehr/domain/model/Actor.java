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
    return canReadEmployee(employee.id(), employee.managerId());
  }

  public boolean canReadEmployee(String id, String managerId) {
    return hr()
        || employeeId.equals(id)
        || (role.equals("MANAGER") && employeeId.equals(managerId));
  }

  public boolean canReadField(Employee employee, String field) {
    return field != null
        && ChangeRequest.FIELDS.contains(field)
        && canRead(employee)
        && (!ChangeRequest.PRIVATE_FIELDS.contains(field)
            || hr()
            || employeeId.equals(employee.id()));
  }

  public boolean canChange(Employee employee, String field) {
    if (employee.status().equals("Ended")) return false;
    if (java.util.Set.of("calendarCode", "legalEntityCode").contains(field))
      return hr() && canRead(employee);
    if (field.equals("name"))
      return canRead(employee) && (hr() || employeeId.equals(employee.id()));
    return canReadField(employee, field)
        && ChangeRequest.FIELDS.contains(field)
        && (field.equals("phone")
            || ChangeRequest.PRIVATE_FIELDS.contains(field)
            || hr()
            || (role.equals("MANAGER") && !employeeId.equals(employee.id())));
  }
}
