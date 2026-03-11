package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.fatigue.AlertFatigueReport;
import com.stablebridge.oncall.domain.model.fatigue.DuplicateGroup;
import com.stablebridge.oncall.domain.model.fatigue.NoisyRule;
import com.stablebridge.oncall.domain.model.fatigue.TuningRecommendation;

import java.util.List;

public final class FatigueFixtures {

    public static NoisyRule aNoisyRule() {
        return new NoisyRule(
                "HighCPUWarning", "tick-ingestor", 45, 0.92, "Increase threshold from 70% to 85%");
    }

    public static DuplicateGroup aDuplicateGroup() {
        return new DuplicateGroup(
                List.of("ALT-010", "ALT-011", "ALT-012"), "tick-ingestor-cpu-alerts");
    }

    public static TuningRecommendation aTuningRecommendation() {
        return new TuningRecommendation("HighCPUWarning", "INCREASE_THRESHOLD", 38, "HIGH");
    }

    public static AlertFatigueReport anAlertFatigueReport() {
        return new AlertFatigueReport(
                120,
                0.65,
                List.of(aNoisyRule()),
                List.of(aDuplicateGroup()),
                List.of(aTuningRecommendation()),
                "65% of alerts auto-resolved without action — recommend tuning HighCPUWarning"
                        + " threshold");
    }

    private FatigueFixtures() {}
}
