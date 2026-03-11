package com.stablebridge.oncall.domain.model.alert;

import com.stablebridge.oncall.domain.model.common.IncidentSeverity;

import java.util.List;

public record IncidentAssessment(
        IncidentSeverity severity,
        String blastRadius,
        String likelyCause,
        List<String> evidence,
        boolean isDeployRelated,
        String recommendation) {}
