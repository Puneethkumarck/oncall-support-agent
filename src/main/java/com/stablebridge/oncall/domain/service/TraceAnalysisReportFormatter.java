package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.trace.BottleneckInfo;
import com.stablebridge.oncall.domain.model.trace.CallChainStep;
import com.stablebridge.oncall.domain.model.trace.CascadeImpact;
import com.stablebridge.oncall.domain.model.trace.TraceAnalysisReport;

public class TraceAnalysisReportFormatter {

    public String format(String service, TraceAnalysisReport report) {
        var sb = new StringBuilder();
        sb.append("# Trace Analysis: ").append(service).append("\n\n");

        sb.append("## Call Chain (").append(report.callChain().size()).append(" steps)\n");
        if (report.callChain().isEmpty()) {
            sb.append("No call chain steps found.\n");
        } else {
            for (CallChainStep step : report.callChain()) {
                sb.append("- **")
                        .append(step.service())
                        .append("** `")
                        .append(step.operation())
                        .append("`: ")
                        .append(step.durationMs())
                        .append("ms [")
                        .append(step.status())
                        .append("]");
                if (step.isBottleneck()) {
                    sb.append(" **BOTTLENECK**");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");

        sb.append("## Bottleneck\n");
        if (report.bottleneck() != null) {
            BottleneckInfo b = report.bottleneck();
            sb.append("- **Service:** ").append(b.service()).append("\n");
            sb.append("- **Reason:** ").append(b.reason()).append("\n");
            if (b.evidence() != null && !b.evidence().isEmpty()) {
                sb.append("- **Evidence:**\n");
                for (String e : b.evidence()) {
                    sb.append("  - ").append(e).append("\n");
                }
            }
        } else {
            sb.append("No bottleneck identified.\n");
        }
        sb.append("\n");

        sb.append("## Cascade Impact (")
                .append(report.cascadeImpact().size())
                .append(")\n");
        if (report.cascadeImpact().isEmpty()) {
            sb.append("No cascade impact detected.\n");
        } else {
            for (CascadeImpact impact : report.cascadeImpact()) {
                sb.append("- **")
                        .append(impact.affectedService())
                        .append(":** ")
                        .append(impact.impactType())
                        .append(" (+")
                        .append(String.format("%.0fms", impact.latencyIncrease()))
                        .append(")\n");
            }
        }
        sb.append("\n");

        sb.append("## Recommendation\n");
        sb.append(report.recommendation()).append("\n");

        return sb.toString();
    }
}
