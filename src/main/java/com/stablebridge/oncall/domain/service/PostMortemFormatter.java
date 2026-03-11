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
        sb.append("- **Duration:** ").append(draft.impact().duration()).append("\n");
        sb.append("- **Affected Users:** ").append(draft.impact().affectedUsers()).append("\n");
        sb.append("- **Affected Services:** ")
            .append(String.join(", ", draft.impact().affectedServices()))
            .append("\n\n");

        sb.append("## Timeline\n");
        for (TimelineEntry entry : draft.timeline()) {
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

        if (!draft.contributingFactors().isEmpty()) {
            sb.append("## Contributing Factors\n");
            for (String factor : draft.contributingFactors()) {
                sb.append("- ").append(factor).append("\n");
            }
            sb.append("\n");
        }

        sb.append("## What Went Well\n");
        for (String item : draft.whatWentWell()) {
            sb.append("- ").append(item).append("\n");
        }
        sb.append("\n");

        sb.append("## What Went Poorly\n");
        for (String item : draft.whatWentPoorly()) {
            sb.append("- ").append(item).append("\n");
        }
        sb.append("\n");

        sb.append("## Action Items\n");
        for (ActionItem item : draft.actionItems()) {
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

        if (!draft.lessonsLearned().isEmpty()) {
            sb.append("## Lessons Learned\n");
            for (String lesson : draft.lessonsLearned()) {
                sb.append("- ").append(lesson).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
