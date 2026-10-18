package com.atlas.modules.corehr.domain.model;

import java.util.*;

public record EmergencyContact(String name, String relationship, String phone) {
  public EmergencyContact {
    var errors = new LinkedHashMap<String, String>();
    if (name == null
        || name.isBlank()
        || name.length() > 120
        || name.codePoints().anyMatch(Character::isISOControl))
      errors.put("contacts", "Each contact needs a name of 1–120 characters without control text.");
    if (relationship == null
        || relationship.isBlank()
        || relationship.length() > 60
        || relationship.codePoints().anyMatch(Character::isISOControl))
      errors.put(
          "contacts", "Each contact needs a relationship of 1–60 characters without control text.");
    if (phone == null
        || !phone.matches("[+0-9 ()-]{7,30}")
        || phone.replaceAll("[^0-9]", "").length() < 7)
      errors.put(
          "contacts",
          "Each contact needs a phone number of 7–30 characters, including at least seven digits.");
    if (!errors.isEmpty()) throw new ChangeRequest.Validation(errors);
    name = name.trim();
    relationship = relationship.trim();
    phone = phone.trim();
  }

  public static List<EmergencyContact> snapshot(List<EmergencyContact> contacts) {
    if (contacts == null || contacts.size() > 4 || contacts.stream().anyMatch(Objects::isNull))
      throw new ChangeRequest.Validation(
          Map.of("contacts", "Provide zero to four additional contacts."));
    var copy = List.copyOf(contacts);
    if (new HashSet<>(copy).size() != copy.size())
      throw new ChangeRequest.Validation(
          Map.of("contacts", "Remove duplicate identical contacts."));
    return copy;
  }
}
