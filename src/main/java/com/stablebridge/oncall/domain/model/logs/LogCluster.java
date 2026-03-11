package com.stablebridge.oncall.domain.model.logs;

import java.time.Instant;

public record LogCluster(
        String exceptionType,
        String fingerprint,
        int count,
        Instant firstSeen,
        Instant lastSeen,
        String sampleStackTrace,
        boolean isNew) {}
