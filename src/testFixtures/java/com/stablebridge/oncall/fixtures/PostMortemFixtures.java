package com.stablebridge.oncall.fixtures;

import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.model.postmortem.ActionItem;
import com.stablebridge.oncall.domain.model.postmortem.ImpactSummary;
import com.stablebridge.oncall.domain.model.postmortem.PostMortemDraft;
import com.stablebridge.oncall.domain.model.postmortem.TimelineEntry;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class PostMortemFixtures {

    public static TimelineEntry aTimelineEntry() {
        return new TimelineEntry(
                Instant.parse("2026-03-10T10:00:00Z"),
                "PagerDuty alert triggered: High error rate on alert-api",
                "PagerDuty");
    }

    public static ImpactSummary anImpactSummary() {
        return new ImpactSummary(
                Duration.ofMinutes(45), 1200, List.of("alert-api", "evaluator"));
    }

    public static ActionItem anActionItem() {
        return new ActionItem(
                "Add null check in PriceEvaluationService.evaluate()",
                "developer@example.com",
                "P1",
                "2026-03-12");
    }

    public static PostMortemDraft aPostMortemDraft() {
        return new PostMortemDraft(
                "SEV2: High error rate on alert-api due to NPE in PriceEvaluationService",
                IncidentSeverity.SEV2,
                anImpactSummary(),
                List.of(aTimelineEntry()),
                "NullPointerException in PriceEvaluationService.evaluate() introduced in deploy"
                        + " deploy-abc123",
                List.of("Missing null check after API response deserialization"),
                List.of("Alert triggered within 5 minutes", "Rollback completed in 10 minutes"),
                List.of("No integration test covering null response scenario"),
                List.of(anActionItem()),
                List.of("Add integration tests for edge cases in price evaluation"));
    }

    private PostMortemFixtures() {}
}
