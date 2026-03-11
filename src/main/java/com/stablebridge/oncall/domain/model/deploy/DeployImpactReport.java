package com.stablebridge.oncall.domain.model.deploy;

import com.stablebridge.oncall.domain.model.common.Confidence;
import com.stablebridge.oncall.domain.model.common.RollbackDecision;

import java.util.List;

public record DeployImpactReport(
        boolean isDeployCaused,
        Confidence confidence,
        List<MetricChange> evidence,
        List<NewErrorSummary> newErrors,
        RollbackDecision rollbackRecommendation,
        String recommendation) {}
