package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.HireRequest;
import java.util.List;

public record HirePage(List<HireRequest> items, long total, int page, int size) {}
