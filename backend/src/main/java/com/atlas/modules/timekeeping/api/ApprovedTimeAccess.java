package com.atlas.modules.timekeeping.api;

import java.time.LocalDate;
import java.util.List;

public interface ApprovedTimeAccess {
  record Day(LocalDate date, int minutes, String activity) {}

  record Source(
      String id,
      String employeeId,
      LocalDate episodeStart,
      LocalDate week,
      long version,
      List<Day> days,
      int totalMinutes) {}

  Source read(String subject, String id);
}
