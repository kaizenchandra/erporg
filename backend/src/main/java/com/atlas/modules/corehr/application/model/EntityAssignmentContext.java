package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.LegalEntity;

public record EntityAssignmentContext(
    String requestId, LegalEntity reference, boolean retirementBlocked) {}
