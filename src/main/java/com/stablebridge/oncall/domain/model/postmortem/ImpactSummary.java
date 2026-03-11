package com.stablebridge.oncall.domain.model.postmortem;

import java.time.Duration;
import java.util.List;

public record ImpactSummary(
        Duration duration, int affectedUsers, List<String> affectedServices) {}
