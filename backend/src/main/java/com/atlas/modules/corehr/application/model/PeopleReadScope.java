package com.atlas.modules.corehr.application.model;

/** Application-authorised query scope. Adapters translate these constraints, never roles. */
public record PeopleReadScope(
    String tenant,
    String subject,
    String employeeId,
    boolean allEmployees,
    boolean directReports,
    java.util.List<String> visibleRequestFields) {
  public static PeopleReadScope of(com.atlas.modules.corehr.domain.model.Actor actor) {
    var fields =
        com.atlas.modules.corehr.domain.model.ChangeRequest.FIELDS.stream()
            .filter(
                f ->
                    actor.hr()
                        || !com.atlas.modules.corehr.domain.model.ChangeRequest.PRIVATE_FIELDS
                            .contains(f))
            .sorted()
            .toList();
    return switch (actor.role()) {
      case "HR" ->
          new PeopleReadScope(
              actor.tenant(), actor.subject(), actor.employeeId(), true, false, fields);
      case "MANAGER" ->
          new PeopleReadScope(
              actor.tenant(), actor.subject(), actor.employeeId(), false, true, fields);
      case "EMPLOYEE" ->
          new PeopleReadScope(
              actor.tenant(), actor.subject(), actor.employeeId(), false, false, fields);
      default -> throw new com.atlas.modules.corehr.domain.model.ChangeRequest.Denied();
    };
  }
}
