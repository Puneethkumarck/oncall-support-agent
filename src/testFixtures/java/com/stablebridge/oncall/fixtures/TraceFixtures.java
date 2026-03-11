package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.trace.BottleneckInfo;
import com.stablebridge.oncall.domain.model.trace.CallChainStep;
import com.stablebridge.oncall.domain.model.trace.CascadeImpact;
import com.stablebridge.oncall.domain.model.trace.TraceAnalysisReport;

import java.util.List;

public final class TraceFixtures {

    public static CallChainStep aCallChainStep() {
        return new CallChainStep("alert-api", "POST /api/v1/alerts", 150, "OK", false);
    }

    public static CallChainStep aBottleneckStep() {
        return new CallChainStep("evaluator", "evaluate", 2500, "TIMEOUT", true);
    }

    public static BottleneckInfo aBottleneckInfo() {
        return new BottleneckInfo(
                "evaluator",
                "Database connection pool exhaustion causing timeouts",
                List.of(
                        "p99 latency 2500ms (threshold 500ms)",
                        "Connection pool saturation at 95%"));
    }

    public static CascadeImpact aCascadeImpact() {
        return new CascadeImpact("alert-api", "LATENCY_INCREASE", 2350.0);
    }

    public static TraceAnalysisReport aTraceAnalysisReport() {
        return new TraceAnalysisReport(
                List.of(aCallChainStep(), aBottleneckStep()),
                aBottleneckInfo(),
                List.of(aCascadeImpact()),
                "Increase evaluator DB connection pool size from 10 to 25");
    }

    private TraceFixtures() {}
}
