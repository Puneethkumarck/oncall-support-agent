package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.slo.BurnContributor;
import com.stablebridge.oncall.domain.model.slo.SLOReport;

public class SLOReportFormatter {

    public String format(String service, SLOReport report) {
        var sb = new StringBuilder();
        sb.append("# SLO Report: ")
            .append(service)
            .append(" [")
            .append(report.sloStatus())
            .append("]\n\n");

        sb.append("## Error Budget\n");
        sb.append("- **Remaining:** ")
            .append(String.format("%.1f%%", report.budgetRemaining()))
            .append("\n");
        sb.append("- **Burn Rate:** ")
            .append(String.format("%.2fx", report.burnRate()))
            .append("\n");
        sb.append("- **Projected Breach:** ")
            .append(
                    report.projectedBreachTime() != null
                            ? report.projectedBreachTime()
                            : "N/A")
            .append("\n\n");

        if (report.topContributors() != null && !report.topContributors().isEmpty()) {
            sb.append("## Top Burn Contributors\n");
            for (BurnContributor contributor : report.topContributors()) {
                sb.append("- **")
                    .append(contributor.endpoint())
                    .append(":** ")
                    .append(contributor.errorType())
                    .append(" (")
                    .append(String.format("%.1f%%", contributor.contributionPercent()))
                    .append(" contribution)\n");
            }
            sb.append("\n");
        }

        if (report.correlatedChange() != null) {
            sb.append("## Correlated Change\n");
            if (report.correlatedChange().isCorrelated()) {
                sb.append("- **Deploy:** ")
                    .append(report.correlatedChange().deployId())
                    .append("\n");
                sb.append("- **Time Delta:** ")
                    .append(report.correlatedChange().timeDelta())
                    .append("\n");
            } else {
                sb.append("- No correlated deployment found.\n");
            }
            sb.append("\n");
        }

        sb.append("## Recommendation\n");
        sb.append(report.recommendation()).append("\n");

        return sb.toString();
    }
}
