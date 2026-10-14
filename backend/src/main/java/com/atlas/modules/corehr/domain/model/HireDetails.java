package com.atlas.modules.corehr.domain.model;

import java.time.LocalDate;

public record HireDetails(
    String name,
    String email,
    String phone,
    String title,
    String department,
    String location,
    String managerId,
    LocalDate startDate) {
  public HireDetails {
    var errors = new java.util.LinkedHashMap<String, String>();
    for (var entry :
        java.util.Map.of(
                "name",
                name == null ? "" : name,
                "title",
                title == null ? "" : title,
                "department",
                department == null ? "" : department,
                "location",
                location == null ? "" : location)
            .entrySet())
      if (entry.getValue().isBlank() || entry.getValue().length() > 120)
        errors.put(entry.getKey(), "Enter 1–120 characters.");
    if (email == null
        || email.length() > 160
        || !email.matches("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+([.][A-Za-z0-9-]+)+"))
      errors.put("email", "Enter a valid work email address.");
    if (phone == null || !phone.matches("[+0-9 ()-]{7,30}"))
      errors.put("phone", "Enter a valid phone number (7–30 characters).");
    if (managerId == null || managerId.isBlank() || managerId.length() > 40)
      errors.put("managerId", "Choose an existing reporting manager.");
    if (startDate == null) errors.put("startDate", "Choose an employment start date.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    name = name.trim();
    email = email.trim().toLowerCase(java.util.Locale.ROOT);
    phone = phone.trim();
    title = title.trim();
    department = department.trim();
    location = location.trim();
  }
}
