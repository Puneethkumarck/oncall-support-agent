package com.stablebridge.oncall.domain.model.trace;

public record CascadeImpact(
        String affectedService, String impactType, double latencyIncrease) {}
