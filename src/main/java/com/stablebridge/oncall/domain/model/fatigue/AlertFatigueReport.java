package com.stablebridge.oncall.domain.model.fatigue;

import java.util.List;

public record AlertFatigueReport(
        int totalAlerts,
        double noisePercentage,
        List<NoisyRule> topNoisyRules,
        List<DuplicateGroup> duplicateGroups,
        List<TuningRecommendation> tuningRecommendations,
        String summary) {}
