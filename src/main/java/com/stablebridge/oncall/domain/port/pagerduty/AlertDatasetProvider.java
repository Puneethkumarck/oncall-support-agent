package com.stablebridge.oncall.domain.port.pagerduty;

import com.stablebridge.oncall.domain.model.alert.AlertSummary;

import java.time.Instant;
import java.util.List;

public interface AlertDatasetProvider {
    List<AlertSummary> fetchAllAlerts(String team, Instant from, Instant to);

    List<AlertSummary> fetchAlertOutcomes(String team, Instant from, Instant to);
}
