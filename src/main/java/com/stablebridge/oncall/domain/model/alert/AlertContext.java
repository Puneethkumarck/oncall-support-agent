package com.stablebridge.oncall.domain.model.alert;

import com.stablebridge.oncall.domain.model.common.IncidentSeverity;

import java.time.Instant;

public record AlertContext(
        String alertId,
        String service,
        IncidentSeverity severity,
        String description,
        Instant triggeredAt,
        String runbookUrl,
        String dedupKey) {}
