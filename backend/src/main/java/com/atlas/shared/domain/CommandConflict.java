package com.atlas.shared.domain;

public class CommandConflict extends RuntimeException {
  public CommandConflict(String message) {
    super(message);
  }
}
