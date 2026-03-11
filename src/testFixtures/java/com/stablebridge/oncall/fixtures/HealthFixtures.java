package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.model.common.Trend;
import com.stablebridge.oncall.domain.model.health.DependencyStatus;
import com.stablebridge.oncall.domain.model.health.Risk;
import com.stablebridge.oncall.domain.model.health.ServiceHealthReport;

import java.util.List;

public final class HealthFixtures {

    public static DependencyStatus aDependencyStatus() {
        return new DependencyStatus("evaluator", HealthStatus.GREEN, 25.0, Trend.STABLE);
    }

    public static Risk aRisk() {
        return new Risk(
                "Error rate approaching SLO threshold",
                IncidentSeverity.SEV3,
                "Monitor error budget burn rate");
    }

    public static ServiceHealthReport aServiceHealthReport() {
        return new ServiceHealthReport(
                HealthStatus.AMBER,
                List.of(MetricsFixtures.anSLISnapshot()),
                MetricsFixtures.anSLOSnapshot(),
                List.of(aDependencyStatus()),
                List.of(aRisk()),
                "Monitor error rate — approaching SLO budget threshold");
    }

    private HealthFixtures() {}
}
