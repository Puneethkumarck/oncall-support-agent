package com.stablebridge.oncall.domain.model.deploy;

import java.time.Instant;

public record RollbackResult(
        String service,
        boolean success,
        String previousRevision,
        String newRevision,
        Instant executedAt,
        String message) {}
