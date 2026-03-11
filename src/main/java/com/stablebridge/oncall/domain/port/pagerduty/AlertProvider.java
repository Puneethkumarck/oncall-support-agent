package com.stablebridge.oncall.domain.port.pagerduty;

import com.stablebridge.oncall.domain.model.alert.AlertContext;

public interface AlertProvider {
    AlertContext fetchAlert(String alertId);
}
