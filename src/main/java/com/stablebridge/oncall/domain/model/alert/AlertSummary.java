package com.stablebridge.oncall.domain.model.alert;

import com.stablebridge.oncall.domain.model.common.AlertStatus;

import java.time.Duration;
import java.time.Instant;

public record AlertSummary(
        String alertId,
        String description,
        AlertStatus status,
        Instant triggeredAt,
        Instant resolvedAt,
        Duration ttResolve) {}
