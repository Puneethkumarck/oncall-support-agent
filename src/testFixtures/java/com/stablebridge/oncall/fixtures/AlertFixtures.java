package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.alert.AlertContext;
import com.stablebridge.oncall.domain.model.alert.AlertHistorySnapshot;
import com.stablebridge.oncall.domain.model.alert.AlertSummary;
import com.stablebridge.oncall.domain.model.alert.IncidentAssessment;
import com.stablebridge.oncall.domain.model.alert.TriageReport;
import com.stablebridge.oncall.domain.model.common.AlertStatus;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class AlertFixtures {

    public static AlertContext anAlertContext() {
        return new AlertContext(
                TestConstants.ALERT_ID,
                TestConstants.SERVICE_NAME,
                IncidentSeverity.SEV2,
                "High error rate on alert-api",
                Instant.parse("2026-03-10T10:00:00Z"),
                "https://runbooks.example.com/alert-api/high-error-rate",
                "alert-api-high-error-rate");
    }

    public static AlertSummary anAlertSummary() {
        return new AlertSummary(
                TestConstants.ALERT_ID,
                "High error rate on alert-api",
                AlertStatus.RESOLVED,
                Instant.parse("2026-03-10T10:00:00Z"),
                Instant.parse("2026-03-10T10:15:00Z"),
                Duration.ofMinutes(15));
    }

    public static AlertHistorySnapshot anAlertHistorySnapshot() {
        return new AlertHistorySnapshot(
                TestConstants.SERVICE_NAME,
                5,
                List.of(anAlertSummary()),
                true,
                Instant.parse("2026-03-09T14:00:00Z"));
    }

    public static IncidentAssessment anIncidentAssessment() {
        return new IncidentAssessment(
                IncidentSeverity.SEV2,
                "alert-api service and downstream consumers",
                "NullPointerException in PriceEvaluationService.evaluate() introduced in deploy"
                        + " deploy-abc123",
                List.of(
                        "Error rate increased 5x after deploy",
                        "New NPE pattern not seen before deploy"),
                true,
                "Rollback deploy deploy-abc123 to previous revision");
    }

    public static TriageReport aTriageReport() {
        return new TriageReport(
                anAlertContext(),
                anIncidentAssessment(),
                MetricsFixtures.aMetricsSnapshot(),
                DeployFixtures.aDeploySnapshot(),
                List.of(LogFixtures.aLogCluster()),
                "# Triage Report\n\nFormatted body");
    }

    private AlertFixtures() {}
}
