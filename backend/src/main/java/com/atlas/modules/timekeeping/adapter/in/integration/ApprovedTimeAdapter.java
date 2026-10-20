package com.atlas.modules.timekeeping.adapter.in.integration;

import com.atlas.modules.timekeeping.api.ApprovedTimeAccess;
import com.atlas.modules.timekeeping.application.port.in.TimeUseCases;

public final class ApprovedTimeAdapter implements ApprovedTimeAccess {
  private final TimeUseCases time;

  public ApprovedTimeAdapter(TimeUseCases time) {
    this.time = time;
  }

  public Source read(String subject, String id) {
    var s = time.approvedSource(subject, id);
    return new Source(
        s.id(),
        s.employeeId(),
        s.episodeStart(),
        s.week(),
        s.version(),
        s.entries().stream().map(e -> new Day(e.date(), e.minutes(), e.code())).toList(),
        s.totalMinutes());
  }
}
