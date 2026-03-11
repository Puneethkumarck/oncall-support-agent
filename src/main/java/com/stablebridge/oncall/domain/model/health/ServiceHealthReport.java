package com.stablebridge.oncall.domain.model.health;

import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.metrics.SLISnapshot;
import com.stablebridge.oncall.domain.model.metrics.SLOSnapshot;

import java.util.List;

public record ServiceHealthReport(
        HealthStatus overallStatus,
        List<SLISnapshot> sliCards,
        SLOSnapshot sloBudget,
        List<DependencyStatus> dependencies,
        List<Risk> risks,
        String recommendation) {}
