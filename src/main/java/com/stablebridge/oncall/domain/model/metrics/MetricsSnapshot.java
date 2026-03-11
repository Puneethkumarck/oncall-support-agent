package com.stablebridge.oncall.domain.model.metrics;

import java.time.Instant;

public record MetricsSnapshot(
        String service,
        double errorRate,
        double latencyP50,
        double latencyP95,
        double latencyP99,
        double throughput,
        double cpuPercent,
        double memoryPercent,
        double saturation,
        Instant collectedAt) {}
