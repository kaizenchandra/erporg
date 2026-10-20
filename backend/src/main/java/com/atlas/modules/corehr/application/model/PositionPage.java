package com.atlas.modules.corehr.application.model;

import java.util.List;

public record PositionPage(List<PositionListing> items, long total, int page, int size) {}
