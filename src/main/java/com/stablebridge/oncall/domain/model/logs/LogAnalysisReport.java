package com.stablebridge.oncall.domain.model.logs;

import com.stablebridge.oncall.domain.model.deploy.DeployCorrelation;

import java.util.List;

public record LogAnalysisReport(
        List<LogCluster> clusters,
        List<NewPattern> newPatterns,
        DeployCorrelation deployCorrelation,
        String recommendation) {}
