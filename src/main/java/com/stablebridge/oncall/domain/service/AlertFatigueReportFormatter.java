package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.fatigue.AlertFatigueReport;
import com.stablebridge.oncall.domain.model.fatigue.DuplicateGroup;
import com.stablebridge.oncall.domain.model.fatigue.NoisyRule;
import com.stablebridge.oncall.domain.model.fatigue.TuningRecommendation;

public class AlertFatigueReportFormatter {

    public String format(String team, int days, AlertFatigueReport report) {
        var sb = new StringBuilder();
        sb.append("# Alert Fatigue Report: ")
                .append(team)
                .append(" (last ")
                .append(days)
                .append(" days)\n\n");

        sb.append("## Overview\n");
        sb.append("- **Total Alerts:** ").append(report.totalAlerts()).append("\n");
        sb.append("- **Noise Percentage:** ")
                .append(String.format("%.1f%%", report.noisePercentage() * 100))
                .append("\n\n");

        if (report.topNoisyRules() != null && !report.topNoisyRules().isEmpty()) {
            sb.append("## Top Noisy Rules\n");
            for (NoisyRule rule : report.topNoisyRules()) {
                sb.append("- **")
                        .append(rule.rule())
                        .append("** (")
                        .append(rule.service())
                        .append("): ")
                        .append(rule.count())
                        .append(" alerts, ")
                        .append(String.format("%.0f%%", rule.autoResolveRate() * 100))
                        .append(" auto-resolved")
                        .append("\n");
            }
            sb.append("\n");
        }

        if (report.duplicateGroups() != null && !report.duplicateGroups().isEmpty()) {
            sb.append("## Duplicate Groups\n");
            for (DuplicateGroup group : report.duplicateGroups()) {
                sb.append("- **")
                        .append(group.suggestedGrouping())
                        .append("**: ")
                        .append(group.alertIds().size())
                        .append(" alerts (")
                        .append(String.join(", ", group.alertIds()))
                        .append(")\n");
            }
            sb.append("\n");
        }

        if (report.tuningRecommendations() != null
                && !report.tuningRecommendations().isEmpty()) {
            sb.append("## Tuning Recommendations\n");
            for (TuningRecommendation rec : report.tuningRecommendations()) {
                sb.append("- [")
                        .append(rec.priority())
                        .append("] **")
                        .append(rec.rule())
                        .append("**: ")
                        .append(rec.action())
                        .append(" (estimated ")
                        .append(rec.expectedReduction())
                        .append("% reduction)\n");
            }
            sb.append("\n");
        }

        sb.append("## Summary\n");
        sb.append(report.summary()).append("\n");

        return sb.toString();
    }
}
