package com.atlas.modules.corehr.application.model;

/** Internal scoped persistence result; raw manager identifier never crosses the web boundary. */
public record OrganisationEntry(OrganisationNode node, String managerId) {}
