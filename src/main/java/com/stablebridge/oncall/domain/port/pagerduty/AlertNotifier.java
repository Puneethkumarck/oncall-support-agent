package com.stablebridge.oncall.domain.port.pagerduty;

public interface AlertNotifier {
    void addIncidentNote(String incidentId, String note);
}
