package com.stablebridge.oncall.domain.port.pagerduty;

import com.stablebridge.oncall.domain.model.alert.AlertHistorySnapshot;

import java.time.Instant;

public interface AlertHistoryProvider {
    AlertHistorySnapshot fetchAlertHistory(String service, Instant from, Instant to);
}
