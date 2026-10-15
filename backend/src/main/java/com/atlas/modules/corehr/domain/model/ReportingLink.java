package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;

public record ReportingLink(String id, String managerId, LocalDate startDate) {}
