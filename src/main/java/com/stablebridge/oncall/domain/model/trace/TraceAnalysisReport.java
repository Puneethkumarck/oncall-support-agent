package com.stablebridge.oncall.domain.model.trace;

import java.util.List;

public record TraceAnalysisReport(
        List<CallChainStep> callChain,
        BottleneckInfo bottleneck,
        List<CascadeImpact> cascadeImpact,
        String recommendation) {}
