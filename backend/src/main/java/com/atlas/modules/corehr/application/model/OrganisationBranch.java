package com.atlas.modules.corehr.application.model;

import java.time.LocalDate;
import java.util.List;

public record OrganisationBranch(
    OrganisationNode parent,
    OrganisationNode manager,
    boolean managerOutsideScope,
    List<OrganisationNode> items,
    long total,
    int page,
    int size,
    LocalDate asOf,
    String mode) {}
