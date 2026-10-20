package com.atlas.modules.benefits.application.model;

import com.atlas.modules.benefits.domain.model.BenefitPlan;
import java.util.List;

public record PlanBatch(List<BenefitPlan> items, long total) {}
