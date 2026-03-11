package com.stablebridge.oncall.domain.model.deploy;

import java.time.Duration;

public record DeployCorrelation(boolean isCorrelated, String deployId, Duration timeDelta) {}
