package com.stablebridge.oncall.domain.model.trace;

public record CallChainStep(
        String service,
        String operation,
        long durationMs,
        String status,
        boolean isBottleneck) {}
