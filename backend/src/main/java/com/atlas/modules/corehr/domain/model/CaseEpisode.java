package com.atlas.modules.corehr.domain.model;

import java.time.*;

public record CaseEpisode(
    String requestId,
    String employeeId,
    int episodeNumber,
    LocalDate episodeStartDate,
    Instant sourceRecordedAt,
    Instant linkedAt,
    String origin,
    String linkedBy,
    String correlationId) {
  public CaseEpisode {
    if (requestId == null
        || employeeId == null
        || episodeNumber != 1
        || episodeStartDate == null
        || sourceRecordedAt == null
        || linkedAt == null
        || linkedBy == null
        || correlationId == null
        || origin == null
        || !java.util.Set.of("COMMAND", "COMPAT_COMMAND", "MIGRATED_V42").contains(origin))
      throw new IllegalArgumentException("Complete supported case-episode provenance is required.");
  }
}
