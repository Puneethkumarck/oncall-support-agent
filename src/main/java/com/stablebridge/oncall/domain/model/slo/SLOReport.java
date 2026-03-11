package com.stablebridge.oncall.domain.model.slo;

import com.stablebridge.oncall.domain.model.common.SLOStatus;
import com.stablebridge.oncall.domain.model.deploy.DeployCorrelation;

import java.util.List;

public record SLOReport(
        String service,
        SLOStatus sloStatus,
        double budgetRemaining,
        double burnRate,
        String projectedBreachTime,
        List<BurnContributor> topContributors,
        DeployCorrelation correlatedChange,
        String recommendation) {}
