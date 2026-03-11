package com.stablebridge.oncall.domain.model.slo;

public record BurnContributor(
        String endpoint, String errorType, double contributionPercent) {}
