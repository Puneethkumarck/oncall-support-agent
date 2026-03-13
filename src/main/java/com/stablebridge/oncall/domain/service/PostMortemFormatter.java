package com.stablebridge.oncall.domain.service;

import com.stablebridge.oncall.domain.model.postmortem.ActionItem;
import com.stablebridge.oncall.domain.model.postmortem.PostMortemDraft;
import com.stablebridge.oncall.domain.model.postmortem.TimelineEntry;

public class PostMortemFormatter {

    public String format(PostMortemDraft draft) {
        var sb = new StringBuilder();
        sb.append("# Post-Mortem: ").append(draft.title()).append("\n\n");
        sb.append("**Severity:** ").append(draft.severity()).append("\n\n");

        sb.append("## Impact\n");
        if (draft.impact() != null) {
            sb.append("- **Duration:** ").append(draft.impact().duration()).append("\n");
            sb.append("- **Affected Users:** ").append(draft.impact().affectedUsers()).append("\n");
            var affectedServices = draft.impact().affectedServices() != null ? draft.impact().affectedServices() : java.util.List.<String>of();
            sb.append("- **Affected Services:** ")
                .append(String.join(", ", affectedServices))
                .append("\n\n");
        } else {
            sb.append("No impact data available.\n\n");
        }

        sb.append("## Timeline\n");
        for (TimelineEntry entry : draft.timeline() != null ? draft.timeline() : java.util.List.<TimelineEntry>of()) {
            sb.append("- **")
                .append(entry.timestamp())
                .append("** ")
                .append(entry.event())
                .append(" (")
                .append(entry.actor())
                .append(")\n");
        }
        sb.append("\n");

        sb.append("## Root Cause\n");
        sb.append(draft.rootCause()).append("\n\n");

        var contributingFactors = draft.contributingFactors() != null ? draft.contributingFactors() : java.util.List.<String>of();
        if (!contributingFactors.isEmpty()) {
            sb.append("## Contributing Factors\n");
            for (String factor : contributingFactors) {
                sb.append("- ").append(factor).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## What Went Well\n");
        for (String item : draft.whatWentWell() != null ? draft.whatWentWell() : java.util.List.<String>of()) {
            sb.append("- ").append(item).append("\n");
        }
        sb.append("\n");

        sb.append("## What Went Poorly\n");
        for (String item : draft.whatWentPoorly() != null ? draft.whatWentPoorly() : java.util.List.<String>of()) {
            sb.append("- ").append(item).append("\n");
        }
        sb.append("\n");

        sb.append("## Action Items\n");
        for (ActionItem item : draft.actionItems() != null ? draft.actionItems() : java.util.List.<ActionItem>of()) {
            sb.append("- [")
                .append(item.priority())
                .append("] ")
                .append(item.description())
                .append(" (Owner: ")
                .append(item.owner())
                .append(", Due: ")
                .append(item.dueDate())
                .append(")\n");
        }
        sb.append("\n");

        var lessonsLearned = draft.lessonsLearned() != null ? draft.lessonsLearned() : java.util.List.<String>of();
        if (!lessonsLearned.isEmpty()) {
            sb.append("## Lessons Learned\n");
            for (String lesson : lessonsLearned) {
                sb.append("- ").append(lesson).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
