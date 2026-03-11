package com.stablebridge.oncall.domain.model.metrics;

import java.time.Instant;

public record SLOSnapshot(
        String sloName,
        double budgetTotal,
        double budgetConsumed,
        double budgetRemaining,
        double burnRate,
        Instant projectedBreach) {}
