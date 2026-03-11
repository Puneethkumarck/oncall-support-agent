package com.stablebridge.oncall.agent.health;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.health.DependencyStatus;
import com.stablebridge.oncall.domain.model.health.ServiceHealthReport;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.model.metrics.SLOSnapshot;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.DependencyGraphProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.HealthCardFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(description = "Assess service health and produce instant RED/AMBER/GREEN health card")
@Slf4j
@RequiredArgsConstructor
public class ServiceHealthAgent {

    private final MetricsProvider metricsProvider;
    private final DependencyGraphProvider dependencyGraphProvider;
    private final LogSearchProvider logSearchProvider;
    private final HealthCardFormatter healthCardFormatter;

    // Blackboard state records
    public record HealthQuery(String service) {}

    public record SLIMetrics(MetricsSnapshot snapshot) {}

    public record Dependencies(List<DependencyStatus> statuses) {}

    public record ErrorSummary(List<LogCluster> clusters) {}

    public record FormattedHealthCard(
            String service, ServiceHealthReport report, String markdown) {}

    @Action(description = "Parse health check request into service identity")
    public HealthQuery parseHealthQuery(UserInput userInput) {
        var service = userInput.getContent().trim();
        log.info("Parsing health query for service: {}", service);
        return new HealthQuery(service);
    }

    @Action(description = "Fetch SLI metrics from Prometheus")
    public SLIMetrics fetchSLIMetrics(HealthQuery query) {
        log.info("Fetching SLI metrics for service: {}", query.service());
        var metrics = metricsProvider.fetchServiceMetrics(query.service(), Instant.now());
        return new SLIMetrics(metrics);
    }

    @Action(description = "Fetch SLO error budget from Prometheus")
    public SLOSnapshot fetchSLOBudget(HealthQuery query) {
        log.info("Fetching SLO budget for service: {}", query.service());
        return metricsProvider.fetchSLOBudget(query.service(), "availability");
    }

    @Action(description = "Fetch dependency health from service mesh metrics")
    public Dependencies fetchDependencyHealth(HealthQuery query) {
        log.info("Fetching dependency health for service: {}", query.service());
        return new Dependencies(dependencyGraphProvider.fetchDependencies(query.service()));
    }

    @Action(description = "Fetch recent error logs from Loki")
    public ErrorSummary fetchRecentErrors(HealthQuery query) {
        log.info("Fetching recent errors for service: {}", query.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(15));
        return new ErrorSummary(
                logSearchProvider.searchLogs(query.service(), from, to, "ERROR"));
    }

    @Action(description = "Assess overall service health using Senior SRE persona")
    public ServiceHealthReport assessHealth(
            SLIMetrics sliMetrics,
            SLOSnapshot sloBudget,
            Dependencies deps,
            ErrorSummary errors,
            HealthQuery query,
            Ai ai) {
        log.info("Assessing health for service: {}", query.service());
        var metrics = sliMetrics.snapshot();
        String prompt = buildAssessmentPrompt(query.service(), metrics, sloBudget, deps, errors);
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.SENIOR_SRE)
                .creating(ServiceHealthReport.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted service health card",
            export =
                    @Export(
                            name = "assessServiceHealth",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format health report as markdown health card")
    public FormattedHealthCard formatHealthCard(
            ServiceHealthReport report, HealthQuery query) {
        log.info(
                "Formatting health card for service: {} — status: {}",
                query.service(),
                report.overallStatus());
        String markdown = healthCardFormatter.format(query.service(), report);
        return new FormattedHealthCard(query.service(), report, markdown);
    }

    private String buildAssessmentPrompt(
            String service,
            MetricsSnapshot metrics,
            SLOSnapshot sloBudget,
            Dependencies deps,
            ErrorSummary errors) {
        int depIssueCount =
                (int) deps.statuses().stream()
                        .filter(d ->
                                d.status() != com.stablebridge.oncall.domain.model.common.HealthStatus.GREEN)
                        .count();
        int newErrorCount =
                (int) errors.clusters().stream().filter(LogCluster::isNew).count();

        return String.format(
                """
                Assess the overall health of service '%s' based on the following data.

                ## Current Metrics
                - Error Rate: %.4f (%.2f%%)
                - Latency P50: %.1fms | P95: %.1fms | P99: %.1fms
                - Throughput: %.0f req/s
                - CPU: %.1f%% | Memory: %.1f%% | Saturation: %.2f

                ## SLO Budget
                - SLO: %s
                - Budget Total: %.1f | Consumed: %.1f | Remaining: %.1f%%
                - Burn Rate: %.2fx
                - Projected Breach: %s

                ## Dependencies (%d total, %d with issues)
                %s

                ## Recent Errors (%d clusters, %d new patterns)
                %s

                Provide your assessment as a ServiceHealthReport with:
                1. overallStatus: RED (outage/SLO breach), AMBER (degraded/risk), or GREEN (healthy)
                2. sliCards: list of SLI snapshots with name, current value, threshold, status (RED/AMBER/GREEN), and trend (IMPROVING/STABLE/DEGRADING/SPIKE)
                3. sloBudget: the SLO budget snapshot
                4. dependencies: list of dependency statuses
                5. risks: identified risks with severity (SEV1-SEV5) and mitigation
                6. recommendation: ONE specific actionable recommendation
                """,
                service,
                metrics.errorRate(),
                metrics.errorRate() * 100,
                metrics.latencyP50(),
                metrics.latencyP95(),
                metrics.latencyP99(),
                metrics.throughput(),
                metrics.cpuPercent(),
                metrics.memoryPercent(),
                metrics.saturation(),
                sloBudget.sloName(),
                sloBudget.budgetTotal(),
                sloBudget.budgetConsumed(),
                sloBudget.budgetRemaining(),
                sloBudget.burnRate(),
                sloBudget.projectedBreach() != null ? sloBudget.projectedBreach() : "N/A",
                deps.statuses().size(),
                depIssueCount,
                formatDependencies(deps),
                errors.clusters().size(),
                newErrorCount,
                formatErrors(errors));
    }

    private String formatDependencies(Dependencies deps) {
        if (deps.statuses().isEmpty()) {
            return "No dependencies discovered.";
        }
        var sb = new StringBuilder();
        for (var dep : deps.statuses()) {
            sb.append(
                    String.format(
                            "- %s: [%s] %.0fms %s%n",
                            dep.name(), dep.status(), dep.latencyMs(), dep.trend()));
        }
        return sb.toString();
    }

    private String formatErrors(ErrorSummary errors) {
        if (errors.clusters().isEmpty()) {
            return "No recent error clusters.";
        }
        var sb = new StringBuilder();
        for (var cluster : errors.clusters()) {
            sb.append(
                    String.format(
                            "- %s (%s): %d occurrences%s%n",
                            cluster.exceptionType(),
                            cluster.fingerprint(),
                            cluster.count(),
                            cluster.isNew() ? " [NEW]" : ""));
        }
        return sb.toString();
    }
}
