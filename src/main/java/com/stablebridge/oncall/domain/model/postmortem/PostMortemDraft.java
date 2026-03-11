package com.stablebridge.oncall.domain.model.postmortem;

import com.stablebridge.oncall.domain.model.common.IncidentSeverity;

import java.util.List;

public record PostMortemDraft(
        String title,
        IncidentSeverity severity,
        ImpactSummary impact,
        List<TimelineEntry> timeline,
        String rootCause,
        List<String> contributingFactors,
        List<String> whatWentWell,
        List<String> whatWentPoorly,
        List<ActionItem> actionItems,
        List<String> lessonsLearned) {}
