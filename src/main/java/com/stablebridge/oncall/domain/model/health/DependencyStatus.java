package com.stablebridge.oncall.domain.model.health;

import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.common.Trend;

public record DependencyStatus(
        String name, HealthStatus status, double latencyMs, Trend trend) {}
