package com.stablebridge.oncall.domain.model.fatigue;

public record NoisyRule(
        String rule,
        String service,
        int count,
        double autoResolveRate,
        String recommendation) {}
