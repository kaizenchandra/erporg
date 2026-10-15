package com.atlas.shared.domain;

public class AccessDenied extends RuntimeException {
  public AccessDenied() {
    super("You do not have permission for this action.");
  }
}
