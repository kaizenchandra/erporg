package com.atlas.modules.benefits.application.model;

/** Server-derived data-selection criteria, never supplied by HTTP clients. */
public record SelectionInboxScope(
    String tenant, String ownEmployeeId, String excludedEmployeeId, String excludedSubject) {}
