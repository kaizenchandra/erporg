package com.atlas.shared.domain;

import java.util.*;

public class InputValidation extends IllegalArgumentException {
  private final Map<String, String> fields;

  public InputValidation(Map<String, String> fields) {
    super("Review the highlighted fields.");
    this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
  }

  public Map<String, String> fields() {
    return fields;
  }
}
