package com.atlas;

import static org.junit.jupiter.api.Assertions.*;

import com.atlas.modules.corehr.domain.model.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class CaseEpisodeDomainTest {
  @Test
  void currentEpisodeBoundaryExplicitlyKeepsNewEpisodeCommandsDisabled() {
    var start = LocalDate.of(2026, 10, 17);
    var original = new EmploymentEpisodeRef(1, start.minusYears(2));
    var next = new EmploymentEpisodeRef(2, start);
    assertEquals(
        original,
        EmploymentEpisodeRef.originalForCase(List.of(original, next), start.minusDays(1)));
    assertThrows(
        ChangeRequest.Conflict.class,
        () -> EmploymentEpisodeRef.originalForCase(List.of(original, next), start));
    assertThrows(
        ChangeRequest.Conflict.class, () -> EmploymentEpisodeRef.originalForCase(List.of(), start));
    assertThrows(IllegalArgumentException.class, () -> new EmploymentEpisodeRef(3, start));
  }

  @Test
  void recordedSubmissionAndLaterMigrationCaptureStayDistinct() {
    var source = Instant.parse("2026-10-01T00:00:00Z");
    var linked = source.plusSeconds(3600);
    var reference =
        new CaseEpisode(
            "case",
            "employee",
            1,
            LocalDate.of(2020, 1, 1),
            source,
            linked,
            "MIGRATED_V42",
            "SYSTEM_MIGRATION_V43",
            "migration-v43");
    assertNotEquals(reference.sourceRecordedAt(), reference.linkedAt());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaseEpisode(
                "case",
                "employee",
                2,
                reference.episodeStartDate(),
                source,
                source,
                "COMMAND",
                "actor",
                "correlation"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new CaseEpisode(
                "case",
                "employee",
                1,
                reference.episodeStartDate(),
                source,
                source,
                null,
                "actor",
                "correlation"));
  }
}
