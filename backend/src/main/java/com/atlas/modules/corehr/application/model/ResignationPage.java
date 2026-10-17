package com.atlas.modules.corehr.application.model;

import com.atlas.modules.corehr.domain.model.Resignation;
import java.util.List;

public record ResignationPage(List<Resignation> items, long total, int page, int size) {}
