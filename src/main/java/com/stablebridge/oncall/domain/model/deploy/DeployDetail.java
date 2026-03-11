package com.stablebridge.oncall.domain.model.deploy;

import java.time.Instant;
import java.util.List;

public record DeployDetail(
        String deployId,
        String commitSha,
        String author,
        String commitMessage,
        String diff,
        List<String> changedFiles,
        Instant deployedAt,
        String previousRevision) {}
