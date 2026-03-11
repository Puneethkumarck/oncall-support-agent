package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.alert.TriageReport;
import com.stablebridge.oncall.domain.model.logs.LogCluster;

public class TriageReportFormatter {

    public String format(TriageReport report) {
        var sb = new StringBuilder();
        sb.append("# Incident Triage Report\n\n");
        sb.append("## Alert\n");
        sb.append("- **Service:** ").append(report.alert().service()).append("\n");
        sb.append("- **Severity:** ").append(report.alert().severity()).append("\n");
        sb.append("- **Description:** ").append(report.alert().description()).append("\n");
        sb.append("- **Triggered:** ").append(report.alert().triggeredAt()).append("\n\n");

        sb.append("## Assessment\n");
        sb.append("- **Likely Cause:** ").append(report.assessment().likelyCause()).append("\n");
        sb.append("- **Blast Radius:** ").append(report.assessment().blastRadius()).append("\n");
        sb.append("- **Deploy Related:** ")
            .append(report.assessment().isDeployRelated())
            .append("\n\n");

        sb.append("## Evidence\n");
        for (String evidence : report.assessment().evidence()) {
            sb.append("- ").append(evidence).append("\n");
        }
        sb.append("\n");

        if (report.topErrors() != null && !report.topErrors().isEmpty()) {
            sb.append("## Top Errors\n");
            for (LogCluster error : report.topErrors()) {
                sb.append("- **")
                    .append(error.exceptionType())
                    .append("** (x")
                    .append(error.count())
                    .append(")\n");
            }
            sb.append("\n");
        }

        sb.append("## Recommendation\n");
        sb.append(report.assessment().recommendation()).append("\n");

        return sb.toString();
    }
}
