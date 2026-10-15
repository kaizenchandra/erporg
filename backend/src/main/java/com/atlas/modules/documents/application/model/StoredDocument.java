package com.atlas.modules.documents.application.model;

public record StoredDocument(
    DocumentView view,
    String objectKey,
    String sha256,
    String requestedBy,
    String idempotencyKey) {}
