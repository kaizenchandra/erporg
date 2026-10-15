package com.atlas.modules.corehr.application.model;

public record OrganisationNode(
    String id,
    String name,
    String title,
    String department,
    String positionCode,
    String status,
    long visibleDirectReports) {}
