package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.ChangeRequest;
import java.util.List;

public record RequestPage(List<ChangeRequest> items, long total, int page, int size) {}
