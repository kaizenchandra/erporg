package com.atlas.modules.corehr.application.model;

/** Application-authorised query scope. Adapters translate these constraints, never roles. */
public record PeopleReadScope(
    String tenant,
    String subject,
    String employeeId,
    boolean allEmployees,
    boolean directReports,
    java.util.List<String> visibleRequestFields) {}
