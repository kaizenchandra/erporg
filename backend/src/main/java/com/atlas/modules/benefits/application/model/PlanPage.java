package com.atlas.modules.benefits.application.model;

import java.time.LocalDate;
import java.util.List;

public record PlanPage(
    List<PlanView> items, long total, int page, int size, LocalDate evaluatedOn) {}
