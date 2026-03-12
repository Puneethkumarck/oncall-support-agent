package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.deploy.RollbackResult;

public class RollbackReportFormatter {

    public String format(RollbackResult result) {
        var sb = new StringBuilder();
        sb.append("# Rollback Report: ")
                .append(result.service())
                .append(" [")
                .append(result.success() ? "SUCCESS" : "FAILED")
                .append("]\n\n");

        sb.append("## Execution\n");
        sb.append("- **Status:** ")
                .append(result.success() ? "Completed" : "Failed")
                .append("\n");
        sb.append("- **Previous Revision:** ")
                .append(result.previousRevision())
                .append("\n");
        sb.append("- **Rolled Back To:** ")
                .append(result.newRevision())
                .append("\n");
        sb.append("- **Executed At:** ")
                .append(result.executedAt())
                .append("\n\n");

        sb.append("## Details\n");
        sb.append(result.message()).append("\n");

        return sb.toString();
    }
}
