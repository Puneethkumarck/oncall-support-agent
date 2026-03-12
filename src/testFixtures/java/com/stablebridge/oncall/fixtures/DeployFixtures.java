package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.common.Confidence;
import com.stablebridge.oncall.domain.model.common.RollbackDecision;
import com.stablebridge.oncall.domain.model.deploy.DeployCorrelation;
import com.stablebridge.oncall.domain.model.deploy.DeployDetail;
import com.stablebridge.oncall.domain.model.deploy.DeployImpactReport;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.deploy.MetricChange;
import com.stablebridge.oncall.domain.model.deploy.NewErrorSummary;
import com.stablebridge.oncall.domain.model.deploy.RollbackHistory;
import com.stablebridge.oncall.domain.model.deploy.RollbackResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class DeployFixtures {

    public static DeploySnapshot aDeploySnapshot() {
        return new DeploySnapshot(
                TestConstants.SERVICE_NAME,
                TestConstants.DEPLOY_ID,
                TestConstants.COMMIT_SHA,
                "developer@example.com",
                Instant.parse("2026-03-10T09:30:00Z"),
                "Synced",
                "Healthy",
                Duration.ofMinutes(30),
                List.of("alert-api:v1.2.3"));
    }

    public static DeployDetail aDeployDetail() {
        return new DeployDetail(
                TestConstants.DEPLOY_ID,
                TestConstants.COMMIT_SHA,
                "developer@example.com",
                "feat: add price threshold validation",
                "diff --git a/src/main/java/...",
                List.of("PriceEvaluationService.java", "AlertConfig.java"),
                Instant.parse("2026-03-10T09:30:00Z"),
                "deploy-prev-789");
    }

    public static DeployCorrelation aDeployCorrelation() {
        return new DeployCorrelation(true, TestConstants.DEPLOY_ID, Duration.ofMinutes(25));
    }

    public static RollbackHistory aRollbackHistory() {
        return new RollbackHistory(
                List.of("deploy-prev-789", "deploy-prev-456"),
                true,
                TestConstants.DEPLOY_ID,
                "deploy-prev-789");
    }

    public static MetricChange aMetricChange() {
        return new MetricChange("error_rate", 0.01, 0.05, 400.0);
    }

    public static NewErrorSummary aNewErrorSummary() {
        return new NewErrorSummary(
                "NullPointerException", 42, Instant.parse("2026-03-10T09:45:00Z"));
    }

    public static RollbackResult aRollbackResult() {
        return new RollbackResult(
                TestConstants.SERVICE_NAME,
                true,
                TestConstants.DEPLOY_ID,
                TestConstants.TARGET_REVISION,
                Instant.parse("2026-03-10T10:00:00Z"),
                "Rollback to revision " + TestConstants.TARGET_REVISION + " completed successfully");
    }

    public static RollbackResult aFailedRollbackResult() {
        return new RollbackResult(
                TestConstants.SERVICE_NAME,
                false,
                TestConstants.DEPLOY_ID,
                TestConstants.TARGET_REVISION,
                Instant.parse("2026-03-10T10:00:00Z"),
                "Rollback failed: ArgoCD sync error");
    }

    public static DeployImpactReport aDeployImpactReport() {
        return new DeployImpactReport(
                true,
                Confidence.HIGH,
                List.of(aMetricChange()),
                List.of(aNewErrorSummary()),
                RollbackDecision.ROLLBACK,
                "Rollback deploy deploy-abc123 — new NPE pattern causing 5x error rate increase");
    }

    private DeployFixtures() {}
}
