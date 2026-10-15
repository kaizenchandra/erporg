package com.atlas.modules.corehr.application.model;

import java.util.List;

public record ManagerPage(List<ManagerCandidate> items, long total, int page, int size) {}
