package com.atlas.modules.documents.domain.model;

import com.atlas.shared.domain.CommandConflict;

public final class DocumentVersionPolicy {
  private DocumentVersionPolicy() {}

  public static void append(String requestedId, String latestId, int latest, int expected) {
    if (!requestedId.equals(latestId) || latest != expected)
      throw new CommandConflict(
          "Document version changed; refresh before uploading a replacement.");
    if (latest >= 100)
      throw new CommandConflict("Version limit reached; contact document operations.");
  }
}
