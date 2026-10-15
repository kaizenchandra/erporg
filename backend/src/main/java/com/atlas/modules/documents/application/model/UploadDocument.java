package com.atlas.modules.documents.application.model;

public record UploadDocument(
    String filename,
    String mediaType,
    String contentBase64,
    String reason,
    String idempotencyKey) {}
