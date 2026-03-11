package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.common.SLOStatus;
import com.stablebridge.oncall.domain.model.common.Trend;
import com.stablebridge.oncall.domain.model.deploy.DeployCorrelation;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.model.metrics.MetricsWindow;
import com.stablebridge.oncall.domain.model.metrics.SLISnapshot;
import com.stablebridge.oncall.domain.model.metrics.SLOSnapshot;
import com.stablebridge.oncall.domain.model.slo.BurnContributor;
import com.stablebridge.oncall.domain.model.slo.SLOReport;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class MetricsFixtures {

    public static MetricsSnapshot aMetricsSnapshot() {
        return new MetricsSnapshot(
                TestConstants.SERVICE_NAME,
                0.05,
                12.0,
                45.0,
                120.0,
                1500.0,
                35.0,
                60.0,
                0.4,
                Instant.parse("2026-03-10T10:00:00Z"));
    }

    public static MetricsWindow aMetricsWindow() {
        return new MetricsWindow(
                Instant.parse("2026-03-10T09:30:00Z"),
                Instant.parse("2026-03-10T10:00:00Z"),
                aMetricsSnapshot());
    }

    public static SLISnapshot anSLISnapshot() {
        return new SLISnapshot("error_rate", 0.05, 0.01, HealthStatus.RED, Trend.DEGRADING);
    }

    public static SLOSnapshot anSLOSnapshot() {
        return new SLOSnapshot(
                TestConstants.SLO_NAME,
                100.0,
                45.0,
                55.0,
                2.5,
                Instant.parse("2026-03-15T00:00:00Z"));
    }

    public static BurnContributor aBurnContributor() {
        return new BurnContributor("/api/v1/alerts", "TimeoutException", 45.0);
    }

    public static SLOReport anSLOReport() {
        return new SLOReport(
                TestConstants.SERVICE_NAME,
                SLOStatus.WARNING,
                55.0,
                2.5,
                "2026-03-15T00:00:00Z",
                List.of(aBurnContributor()),
                new DeployCorrelation(
                        true, TestConstants.DEPLOY_ID, Duration.ofMinutes(25)),
                "Investigate high error rate on /api/v1/alerts endpoint");
    }

    private MetricsFixtures() {}
}
