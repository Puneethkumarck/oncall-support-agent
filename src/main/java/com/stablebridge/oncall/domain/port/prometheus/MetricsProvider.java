package com.stablebridge.oncall.domain.port.prometheus;

import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.model.metrics.MetricsWindow;
import com.stablebridge.oncall.domain.model.metrics.SLOSnapshot;
import com.stablebridge.oncall.domain.model.slo.BurnContributor;

import java.time.Instant;
import java.util.List;

public interface MetricsProvider {
    MetricsSnapshot fetchServiceMetrics(String service, Instant at);

    MetricsWindow fetchMetricsWindow(String service, Instant from, Instant to);

    SLOSnapshot fetchSLOBudget(String service, String sloName);

    List<BurnContributor> fetchBurnContributors(String service, Instant from, Instant to);

    List<SLOSnapshot> fetchBurnHistory(String service, Instant from, Instant to);
}
