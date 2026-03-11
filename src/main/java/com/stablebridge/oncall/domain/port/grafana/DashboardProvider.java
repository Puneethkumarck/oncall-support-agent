package com.stablebridge.oncall.domain.port.grafana;

import java.time.Instant;
import java.util.List;

public interface DashboardProvider {
    List<String> fetchAnnotations(String dashboardUid, Instant from, Instant to);
}
