package com.stablebridge.oncall.domain.model.metrics;

import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.common.Trend;

public record SLISnapshot(
        String name, double value, double threshold, HealthStatus status, Trend trend) {}
