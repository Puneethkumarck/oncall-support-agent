package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.deploy.DeployCorrelation;
import com.stablebridge.oncall.domain.model.logs.LogAnalysisReport;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.logs.NewPattern;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class LogFixtures {

    public static LogCluster aLogCluster() {
        return new LogCluster(
                "NullPointerException",
                "NPE-PriceEval-001",
                42,
                Instant.parse("2026-03-10T09:45:00Z"),
                Instant.parse("2026-03-10T10:00:00Z"),
                "java.lang.NullPointerException at"
                    + " PriceEvaluationService.evaluate(PriceEvaluationService.java:87)",
                true);
    }

    public static NewPattern aNewPattern() {
        return new NewPattern(
                "NullPointerException in PriceEvaluationService",
                0.95,
                "Null check missing after API response deserialization");
    }

    public static LogAnalysisReport aLogAnalysisReport() {
        return new LogAnalysisReport(
                List.of(aLogCluster()),
                List.of(aNewPattern()),
                new DeployCorrelation(true, TestConstants.DEPLOY_ID, Duration.ofMinutes(15)),
                "New NPE pattern correlated with deploy deploy-abc123");
    }

    private LogFixtures() {}
}
