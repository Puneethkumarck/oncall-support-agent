package com.stablebridge.oncall.agent.slo;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.metrics.SLOSnapshot;
import com.stablebridge.oncall.domain.model.slo.BurnContributor;
import com.stablebridge.oncall.domain.model.slo.SLOReport;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.SLOReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(description = "Monitor SLO burn rate and project error budget breach timing")
@Slf4j
@RequiredArgsConstructor
public class SLOMonitorAgent {

    private final MetricsProvider metricsProvider;
    private final DeployHistoryProvider deployHistoryProvider;
    private final SLOReportFormatter sloReportFormatter;

    // Blackboard state records
    public record SLOQuery(String service) {}

    public record ErrorBudgetSnapshot(SLOSnapshot snapshot) {}

    public record BurnContributors(List<BurnContributor> contributors) {}

    public record ChangeSnapshot(List<DeploySnapshot> deploys) {}

    public record FormattedSLOReport(String service, SLOReport report, String markdown) {}

    @Action(description = "Parse SLO check request into service identity")
    public SLOQuery parseSLOQuery(UserInput userInput) {
        var service = userInput.getContent().trim();
        log.info("Parsing SLO query for service: {}", service);
        return new SLOQuery(service);
    }

    @Action(description = "Fetch error budget snapshot from Prometheus")
    public ErrorBudgetSnapshot fetchErrorBudget(SLOQuery query) {
        log.info("Fetching error budget for service: {}", query.service());
        var snapshot = metricsProvider.fetchSLOBudget(query.service(), "availability");
        return new ErrorBudgetSnapshot(snapshot);
    }

    @Action(description = "Fetch top burn contributors from Prometheus")
    public BurnContributors fetchBurnContributors(SLOQuery query) {
        log.info("Fetching burn contributors for service: {}", query.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(1));
        var contributors = metricsProvider.fetchBurnContributors(query.service(), from, to);
        return new BurnContributors(contributors);
    }

    @Action(description = "Fetch recent deployments from ArgoCD")
    public ChangeSnapshot fetchRecentChanges(SLOQuery query) {
        log.info("Fetching recent changes for service: {}", query.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(24));
        var deploys = deployHistoryProvider.fetchDeploysInWindow(query.service(), from, to);
        return new ChangeSnapshot(deploys);
    }

    @Action(description = "Analyze SLO health using Senior SRE persona")
    public SLOReport analyzeSLOHealth(
            ErrorBudgetSnapshot budget,
            BurnContributors contributors,
            ChangeSnapshot changes,
            SLOQuery query,
            Ai ai) {
        log.info("Analyzing SLO health for service: {}", query.service());
        String prompt = buildAnalysisPrompt(query.service(), budget, contributors, changes);
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.SENIOR_SRE)
                .creating(SLOReport.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted SLO burn rate report",
            export =
                    @Export(
                            name = "monitorSLO",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format SLO report as markdown")
    public FormattedSLOReport formatReport(SLOReport report, SLOQuery query) {
        log.info(
                "Formatting SLO report for service: {} — status: {}",
                query.service(),
                report.sloStatus());
        String markdown = sloReportFormatter.format(query.service(), report);
        return new FormattedSLOReport(query.service(), report, markdown);
    }

    private String buildAnalysisPrompt(
            String service,
            ErrorBudgetSnapshot budget,
            BurnContributors contributors,
            ChangeSnapshot changes) {
        var snapshot = budget.snapshot();

        return String.format(
                """
                Analyze the SLO health of service '%s' based on the following data.

                ## Error Budget
                - SLO: %s
                - Budget Total: %.1f | Consumed: %.1f | Remaining: %.1f%%
                - Burn Rate: %.2fx
                - Projected Breach: %s

                ## Top Burn Contributors (%d endpoints)
                %s

                ## Recent Changes (%d deploys in last 24h)
                %s

                Provide your analysis as an SLOReport with:
                1. sloStatus: HEALTHY (budget > 50%%), WARNING (budget 20-50%%), CRITICAL (budget < 20%%), or BREACHED (budget exhausted)
                2. budgetRemaining: percentage of error budget remaining
                3. burnRate: current burn rate multiplier
                4. projectedBreachTime: when the budget will be exhausted (or null if healthy)
                5. topContributors: list of endpoints/error types consuming the most budget
                6. correlatedChange: whether a recent deploy correlates with budget burn
                7. recommendation: ONE specific actionable recommendation
                """,
                service,
                snapshot.sloName(),
                snapshot.budgetTotal(),
                snapshot.budgetConsumed(),
                snapshot.budgetRemaining(),
                snapshot.burnRate(),
                snapshot.projectedBreach() != null ? snapshot.projectedBreach() : "N/A",
                contributors.contributors().size(),
                formatContributors(contributors),
                changes.deploys().size(),
                formatChanges(changes));
    }

    private String formatContributors(BurnContributors contributors) {
        if (contributors.contributors().isEmpty()) {
            return "No burn contributors identified.";
        }
        var sb = new StringBuilder();
        for (var contributor : contributors.contributors()) {
            sb.append(
                    String.format(
                            "- %s: %s (%.1f%% contribution)%n",
                            contributor.endpoint(),
                            contributor.errorType(),
                            contributor.contributionPercent()));
        }
        return sb.toString();
    }

    private String formatChanges(ChangeSnapshot changes) {
        if (changes.deploys().isEmpty()) {
            return "No recent deployments.";
        }
        var sb = new StringBuilder();
        for (var deploy : changes.deploys()) {
            sb.append(
                    String.format(
                            "- %s by %s at %s [%s] %s%n",
                            deploy.lastDeployId(),
                            deploy.author(),
                            deploy.deployedAt(),
                            deploy.syncStatus(),
                            deploy.health()));
        }
        return sb.toString();
    }
}
