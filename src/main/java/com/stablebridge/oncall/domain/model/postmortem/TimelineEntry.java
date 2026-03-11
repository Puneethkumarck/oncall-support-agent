package com.stablebridge.oncall.domain.model.postmortem;

import java.time.Instant;

public record TimelineEntry(Instant timestamp, String event, String actor) {}
