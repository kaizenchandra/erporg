package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;
import java.util.*;

public record RehireDetails(
    LocalDate startDate, String title, String department, String location, String managerId) {
  public RehireDetails {
    var errors = new LinkedHashMap<String, String>();
    for (var f :
        Map.of(
                "title",
                title == null ? "" : title,
                "department",
                department == null ? "" : department,
                "location",
                location == null ? "" : location)
            .entrySet())
      if (f.getValue().isBlank() || f.getValue().length() > 120)
        errors.put(f.getKey(), "Enter 1–120 characters.");
    if (startDate == null) errors.put("startDate", "Choose the new employment start date.");
    if (managerId == null || managerId.isBlank() || managerId.length() > 40)
      errors.put("managerId", "Choose an existing reporting manager.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    title = title.trim();
    department = department.trim();
    location = location.trim();
  }
}
