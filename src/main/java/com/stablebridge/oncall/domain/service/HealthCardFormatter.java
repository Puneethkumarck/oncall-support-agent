package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.health.DependencyStatus;
import com.stablebridge.oncall.domain.model.health.Risk;
import com.stablebridge.oncall.domain.model.health.ServiceHealthReport;
import com.stablebridge.oncall.domain.model.metrics.SLISnapshot;

public class HealthCardFormatter {

    public String format(String service, ServiceHealthReport report) {
        var sb = new StringBuilder();
        sb.append("# Service Health: ")
            .append(service)
            .append(" [")
            .append(report.overallStatus())
            .append("]\n\n");

        sb.append("## SLI Summary\n");
        for (SLISnapshot sli : report.sliCards()) {
            sb.append("- **")
                .append(sli.name())
                .append(":** ")
                .append(sli.value())
                .append(" (threshold: ")
                .append(sli.threshold())
                .append(") [")
                .append(sli.status())
                .append("] ")
                .append(sli.trend())
                .append("\n");
        }
        sb.append("\n");

        if (report.sloBudget() != null) {
            sb.append("## SLO Budget\n");
            sb.append("- **Remaining:** ")
                .append(String.format("%.1f%%", report.sloBudget().budgetRemaining()))
                .append("\n");
            sb.append("- **Burn Rate:** ")
                .append(String.format("%.2fx", report.sloBudget().burnRate()))
                .append("\n\n");
        }

        if (report.dependencies() != null && !report.dependencies().isEmpty()) {
            sb.append("## Dependencies\n");
            for (DependencyStatus dep : report.dependencies()) {
                sb.append("- **")
                    .append(dep.name())
                    .append(":** [")
                    .append(dep.status())
                    .append("] ")
                    .append(String.format("%.0fms", dep.latencyMs()))
                    .append(" ")
                    .append(dep.trend())
                    .append("\n");
            }
            sb.append("\n");
        }

        if (report.risks() != null && !report.risks().isEmpty()) {
            sb.append("## Risks\n");
            for (Risk risk : report.risks()) {
                sb.append("- [")
                    .append(risk.severity())
                    .append("] ")
                    .append(risk.description())
                    .append("\n");
            }
            sb.append("\n");
        }

        sb.append("## Recommendation\n");
        sb.append(report.recommendation()).append("\n");

        return sb.toString();
    }
}
