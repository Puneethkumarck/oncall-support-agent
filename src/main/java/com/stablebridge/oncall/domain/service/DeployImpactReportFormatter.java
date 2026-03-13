package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.deploy.DeployImpactReport;
import com.stablebridge.oncall.domain.model.deploy.MetricChange;
import com.stablebridge.oncall.domain.model.deploy.NewErrorSummary;

public class DeployImpactReportFormatter {

    public String format(String service, DeployImpactReport report) {
        var sb = new StringBuilder();
        sb.append("# Deploy Impact: ")
                .append(service)
                .append(" [")
                .append(report.rollbackRecommendation())
                .append("]\n\n");

        sb.append("## Assessment\n");
        sb.append("- **Deploy Caused Issue:** ")
                .append(report.isDeployCaused() ? "Yes" : "No")
                .append("\n");
        sb.append("- **Confidence:** ").append(report.confidence()).append("\n");
        sb.append("- **Rollback Decision:** ")
                .append(report.rollbackRecommendation())
                .append("\n\n");

        var metricChanges = report.evidence() != null ? report.evidence() : java.util.List.<MetricChange>of();
        sb.append("## Metric Changes (")
                .append(metricChanges.size())
                .append(")\n");
        if (metricChanges.isEmpty()) {
            sb.append("No significant metric changes detected.\n");
        } else {
            for (MetricChange change : metricChanges) {
                sb.append("- **")
                        .append(change.metric())
                        .append(":** ")
                        .append(String.format("%.4f", change.before()))
                        .append(" -> ")
                        .append(String.format("%.4f", change.after()))
                        .append(" (")
                        .append(String.format("%.1f%%", change.changePercent()))
                        .append(")\n");
            }
        }
        sb.append("\n");

        var newErrors = report.newErrors() != null ? report.newErrors() : java.util.List.<NewErrorSummary>of();
        sb.append("## New Errors (")
                .append(newErrors.size())
                .append(")\n");
        if (newErrors.isEmpty()) {
            sb.append("No new errors detected after deployment.\n");
        } else {
            for (NewErrorSummary error : newErrors) {
                sb.append("- **")
                        .append(error.exceptionType())
                        .append(":** ")
                        .append(error.count())
                        .append(" occurrences (first seen: ")
                        .append(error.firstSeen())
                        .append(")\n");
            }
        }
        sb.append("\n");

        sb.append("## Recommendation\n");
        sb.append(report.recommendation()).append("\n");

        return sb.toString();
    }
}
