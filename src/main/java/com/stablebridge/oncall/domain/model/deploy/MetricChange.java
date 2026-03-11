package com.stablebridge.oncall.domain.model.deploy;

public record MetricChange(String metric, double before, double after, double changePercent) {}
