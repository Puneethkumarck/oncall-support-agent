package com.stablebridge.oncall.domain.model.deploy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public record DeploySnapshot(
        String appName,
        String lastDeployId,
        String commitSha,
        String author,
        Instant deployedAt,
        String syncStatus,
        String health,
        Duration timeSinceDeploy,
        List<String> changedImages) {}
