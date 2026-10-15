package com.atlas.modules.benefits.application.model;

public record SelectionPage(java.util.List<SelectionView> items, long total, int page, int size) {}
