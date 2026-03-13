package com.stablebridge.oncall.domain.model.deploy;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.time.Duration;

@JsonDeserialize(using = DeployCorrelationDeserializer.class)
public record DeployCorrelation(boolean isCorrelated, String deployId, Duration timeDelta) {}
