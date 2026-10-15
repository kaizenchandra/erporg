package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.Position;
import java.util.List;

public record PositionPage(List<Position> items, long total, int page, int size) {}
