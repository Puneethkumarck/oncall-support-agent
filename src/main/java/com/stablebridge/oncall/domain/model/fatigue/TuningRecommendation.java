package com.stablebridge.oncall.domain.model.fatigue;

public record TuningRecommendation(
        String rule, String action, int expectedReduction, String priority) {}
