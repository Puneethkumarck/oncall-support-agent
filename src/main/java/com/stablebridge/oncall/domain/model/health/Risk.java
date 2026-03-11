package com.stablebridge.oncall.domain.model.health;

import com.stablebridge.oncall.domain.model.common.IncidentSeverity;

public record Risk(String description, IncidentSeverity severity, String mitigation) {}
