package com.stablebridge.oncall.domain.model.alert;

import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;

import java.util.List;

public record TriageReport(
        AlertContext alert,
        IncidentAssessment assessment,
        MetricsSnapshot metrics,
        DeploySnapshot recentDeploy,
        List<LogCluster> topErrors,
        String formattedBody) {}
