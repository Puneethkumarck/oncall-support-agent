package com.stablebridge.oncall.domain.model.metrics;

import java.time.Instant;

public record MetricsWindow(Instant from, Instant to, MetricsSnapshot snapshot) {}
