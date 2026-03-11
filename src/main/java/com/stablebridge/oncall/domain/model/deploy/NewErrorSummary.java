package com.stablebridge.oncall.domain.model.deploy;

import java.time.Instant;

public record NewErrorSummary(String exceptionType, int count, Instant firstSeen) {}
