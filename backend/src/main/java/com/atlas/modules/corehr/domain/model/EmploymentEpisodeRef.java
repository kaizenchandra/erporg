package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;
import java.util.List;

public record EmploymentEpisodeRef(int number, LocalDate startDate) {
  public EmploymentEpisodeRef {
    if (number < 1 || number > 2 || startDate == null)
      throw new IllegalArgumentException("A supported episode and start date are required.");
  }

  public static EmploymentEpisodeRef originalForCase(
      List<EmploymentEpisodeRef> episodes, LocalDate asOf) {
    var original =
        episodes.stream()
            .filter(e -> e.number() == 1)
            .findFirst()
            .orElseThrow(
                () -> new ChangeRequest.Conflict("Original episode reference is unavailable."));
    if (episodes.stream().anyMatch(e -> e.number() > 1 && !e.startDate().isAfter(asOf)))
      throw new ChangeRequest.Conflict(
          "New-episode resignation requires the coordinated episode lifecycle workflow; it is not enabled yet.");
    return original;
  }
}
