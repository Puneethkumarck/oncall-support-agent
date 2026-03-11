package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.logs.LogAnalysisReport;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.logs.NewPattern;

public class LogAnalysisReportFormatter {

    public String format(String service, LogAnalysisReport report) {
        var sb = new StringBuilder();
        sb.append("# Log Analysis: ").append(service).append("\n\n");

        sb.append("## Error Clusters (").append(report.clusters().size()).append(")\n");
        if (report.clusters().isEmpty()) {
            sb.append("No error clusters found.\n");
        } else {
            for (LogCluster cluster : report.clusters()) {
                sb.append("- **")
                        .append(cluster.exceptionType())
                        .append("** (`")
                        .append(cluster.fingerprint())
                        .append("`): ")
                        .append(cluster.count())
                        .append(" occurrences");
                if (cluster.isNew()) {
                    sb.append(" [NEW]");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        sb.append("## New Patterns (").append(report.newPatterns().size()).append(")\n");
        if (report.newPatterns().isEmpty()) {
            sb.append("No new patterns detected.\n");
        } else {
            for (NewPattern pattern : report.newPatterns()) {
                sb.append("- **")
                        .append(pattern.pattern())
                        .append("** (confidence: ")
                        .append(String.format("%.0f%%", pattern.confidence() * 100))
                        .append(") — ")
                        .append(pattern.possibleCause())
                        .append("\n");
            }
        }
        sb.append("\n");

        sb.append("## Deploy Correlation\n");
        if (report.deployCorrelation() != null && report.deployCorrelation().isCorrelated()) {
            sb.append("- **Correlated:** Yes — deploy `")
                    .append(report.deployCorrelation().deployId())
                    .append("` (")
                    .append(report.deployCorrelation().timeDelta().toMinutes())
                    .append("m ago)\n");
        } else {
            sb.append("- **Correlated:** No\n");
        }
        sb.append("\n");

        sb.append("## Recommendation\n");
        sb.append(report.recommendation()).append("\n");

        return sb.toString();
    }
}
