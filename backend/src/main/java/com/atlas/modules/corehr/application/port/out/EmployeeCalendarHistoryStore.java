package com.atlas.modules.corehr.application.port.out;

import com.atlas.modules.corehr.application.model.EmployeeCalendarModels.*;
import java.time.LocalDate;

public interface EmployeeCalendarHistoryStore {
  History history(
      String tenant, String employeeId, LocalDate asOf, LocalDate episodeStart, HistoryQuery query);
}
