package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.logs.LogAnalysisReport;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.logs.NewPattern;

public class LogAnalysisReportFormatter {

    public String format(String service, LogAnalysisReport report) {
        var sb = new StringBuilder();
        sb.append("# Log Analysis: ").append(service).append("\n\n");

        var clusters = report.clusters() != null ? report.clusters() : java.util.List.<LogCluster>of();
        sb.append("## Error Clusters (").append(clusters.size()).append(")\n");
        if (clusters.isEmpty()) {
            sb.append("No error clusters found.\n");
        } else {
            for (LogCluster cluster : clusters) {
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

        var patterns = report.newPatterns() != null ? report.newPatterns() : java.util.List.<NewPattern>of();
        sb.append("## New Patterns (").append(patterns.size()).append(")\n");
        if (patterns.isEmpty()) {
            sb.append("No new patterns detected.\n");
        } else {
            for (NewPattern pattern : patterns) {
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
        sb.append(report.recommendation() != null ? report.recommendation() : "No recommendation available.").append("\n");

        return sb.toString();
    }
}
