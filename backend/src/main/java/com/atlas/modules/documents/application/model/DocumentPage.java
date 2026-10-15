package com.atlas.modules.documents.application.model;

public record DocumentPage(java.util.List<DocumentView> items, long total, int page, int size) {}
