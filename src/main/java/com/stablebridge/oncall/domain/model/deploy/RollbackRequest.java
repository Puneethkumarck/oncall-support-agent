package com.stablebridge.oncall.domain.model.deploy;

public record RollbackRequest(
        String service,
        String targetRevision,
        String currentRevision,
        String reason) {}
