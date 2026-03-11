package com.stablebridge.oncall.domain.model.alert;

import java.time.Instant;
import java.util.List;

public record AlertHistorySnapshot(
        String service,
        int totalAlerts,
        List<AlertSummary> recentAlerts,
        boolean isRecurring,
        Instant lastOccurrence) {}
