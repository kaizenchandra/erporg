package com.atlas.modules.documents.domain.model;

import com.atlas.shared.domain.InputValidation;
import java.nio.*;
import java.nio.charset.*;
import java.util.Map;

public final class DocumentPolicy {
  public static final int MAX_BYTES = 16384;

  private DocumentPolicy() {}

  public static void validate(String name, String type, byte[] bytes) {
    if (name == null
        || !name.matches("[\\p{L}\\p{N} _.-]{1,115}[.]txt")
        || name.startsWith(".")
        || name.contains(".."))
      throw new InputValidation(
          Map.of(
              "filename",
              "Use a .txt filename without paths, controls or consecutive dots (maximum 119 characters)."));
    if (!"text/plain".equals(type))
      throw new InputValidation(Map.of("mediaType", "Only text/plain is supported in this phase."));
    if (bytes == null || bytes.length < 1 || bytes.length > MAX_BYTES)
      throw new InputValidation(Map.of("file", "Choose a text file of 1–16384 bytes."));
    try {
      var text =
          StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(bytes));
      if (text.chars()
          .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
        throw new CharacterCodingException();
    } catch (CharacterCodingException e) {
      throw new InputValidation(
          Map.of("file", "File must be valid UTF-8 text without binary controls."));
    }
  }

  public static String availability(String verdict) {
    if (verdict == null) throw new IllegalArgumentException("Scanner verdict required.");
    return switch (verdict) {
      case "CLEAN" -> "AVAILABLE";
      case "INFECTED" -> "REJECTED";
      case "UNAVAILABLE" -> "QUARANTINED";
      default -> throw new IllegalArgumentException("Unrecognised scanner verdict.");
    };
  }

  public static void retry(String status, int currentAttempt, Integer expected) {
    if (expected == null)
      throw new InputValidation(Map.of("expectedAttempt", "A scan attempt version is required."));
    if (!status.equals("QUARANTINED"))
      throw new com.atlas.shared.domain.CommandConflict(
          "Only quarantined documents can be rescanned.");
    if (currentAttempt != expected)
      throw new com.atlas.shared.domain.CommandConflict(
          "Scan status changed; refresh documents before retrying.");
    if (currentAttempt >= 3)
      throw new com.atlas.shared.domain.CommandConflict(
          "Three scan retries have been used; contact document operations.");
  }
}
