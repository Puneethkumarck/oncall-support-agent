package com.stablebridge.oncall.agent.deploy;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.deploy.DeployDetail;
import com.stablebridge.oncall.domain.model.deploy.DeployImpactReport;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.DeployImpactReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(
        description =
                "Analyze deployment impact by comparing pre/post metrics, new errors, and deploy details to recommend rollback decision")
@Slf4j
@RequiredArgsConstructor
public class DeployImpactAgent {

    private final DeployHistoryProvider deployHistoryProvider;
    private final MetricsProvider metricsProvider;
    private final LogSearchProvider logSearchProvider;
    private final DeployImpactReportFormatter deployImpactReportFormatter;

    // Blackboard state records
    public record DeployImpactQuery(String service, int lookbackMinutes) {}

    public record DeployDetailData(DeployDetail detail) {}

    public record PreDeployMetrics(MetricsSnapshot snapshot) {}

    public record PostDeployMetrics(MetricsSnapshot snapshot) {}

    public record NewErrorSnapshot(List<LogCluster> clusters) {}

    public record FormattedDeployImpact(
            String service, DeployImpactReport report, String markdown) {}

    @Action(description = "Parse deploy impact request into structured query")
    public DeployImpactQuery parseDeployQuery(UserInput userInput) {
        var content = userInput.getContent().trim();
        log.info("Parsing deploy impact query from input: {}", content);

        var parts = content.split("\\s+");
        String service = parts[0];
        int lookbackMinutes = 30;
        if (parts.length > 1) {
            try {
                lookbackMinutes = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.debug(
                        "Could not parse lookback window '{}', using default 30m",
                        parts[1]);
            }
        }
        return new DeployImpactQuery(service, lookbackMinutes);
    }

    @Action(description = "Fetch deploy detail from ArgoCD for the latest deployment")
    public DeployDetailData fetchDeployDetail(DeployImpactQuery query) {
        log.info("Fetching deploy detail for service: {}", query.service());
        DeploySnapshot snapshot = deployHistoryProvider.fetchLatestDeploy(query.service());
        DeployDetail detail =
                deployHistoryProvider.fetchDeployDetail(
                        query.service(), snapshot.lastDeployId());
        return new DeployDetailData(detail);
    }

    @Action(description = "Fetch pre-deployment metrics from Prometheus")
    public PreDeployMetrics fetchPreDeployMetrics(DeployImpactQuery query) {
        log.info("Fetching pre-deploy metrics for service: {}", query.service());
        DeploySnapshot snapshot = deployHistoryProvider.fetchLatestDeploy(query.service());
        Instant preDeployTime = snapshot.deployedAt().minus(Duration.ofMinutes(5));
        MetricsSnapshot metrics =
                metricsProvider.fetchServiceMetrics(query.service(), preDeployTime);
        return new PreDeployMetrics(metrics);
    }

    @Action(description = "Fetch post-deployment metrics from Prometheus")
    public PostDeployMetrics fetchPostDeployMetrics(DeployImpactQuery query) {
        log.info("Fetching post-deploy metrics for service: {}", query.service());
        MetricsSnapshot metrics =
                metricsProvider.fetchServiceMetrics(query.service(), Instant.now());
        return new PostDeployMetrics(metrics);
    }

    @Action(description = "Fetch new errors from Loki that appeared after deployment")
    public NewErrorSnapshot fetchNewErrors(DeployImpactQuery query) {
        log.info("Fetching new errors for service: {}", query.service());
        DeploySnapshot snapshot = deployHistoryProvider.fetchLatestDeploy(query.service());
        Instant from = snapshot.deployedAt();
        Instant to = Instant.now();
        List<LogCluster> clusters =
                logSearchProvider.searchLogs(query.service(), from, to, "ERROR");
        return new NewErrorSnapshot(clusters);
    }

    @Action(
            description =
                    "Analyze deployment impact using Senior SRE persona to determine if deploy caused the issue")
    public DeployImpactReport analyzeDeployImpact(
            DeployDetailData deployDetail,
            PreDeployMetrics preMetrics,
            PostDeployMetrics postMetrics,
            NewErrorSnapshot newErrors,
            DeployImpactQuery query,
            Ai ai) {
        log.info("Analyzing deploy impact for service: {}", query.service());
        String prompt =
                buildAnalysisPrompt(
                        query, deployDetail, preMetrics, postMetrics, newErrors);
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.SENIOR_SRE)
                .creating(DeployImpactReport.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted deployment impact report",
            export =
                    @Export(
                            name = "analyzeDeployImpact",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format deploy impact report as markdown")
    public FormattedDeployImpact formatReport(
            DeployImpactReport report, DeployImpactQuery query) {
        log.info(
                "Formatting deploy impact report for service: {} — deploy caused: {}, confidence: {}",
                query.service(),
                report.isDeployCaused(),
                report.confidence());
        String markdown =
                deployImpactReportFormatter.format(query.service(), report);
        return new FormattedDeployImpact(query.service(), report, markdown);
    }

    private String buildAnalysisPrompt(
            DeployImpactQuery query,
            DeployDetailData deployDetail,
            PreDeployMetrics preMetrics,
            PostDeployMetrics postMetrics,
            NewErrorSnapshot newErrors) {
        var detail = deployDetail.detail();
        var pre = preMetrics.snapshot();
        var post = postMetrics.snapshot();

        double errorRateChange =
                pre.errorRate() > 0
                        ? ((post.errorRate() - pre.errorRate()) / pre.errorRate()) * 100
                        : 0.0;
        double latencyP99Change =
                pre.latencyP99() > 0
                        ? ((post.latencyP99() - pre.latencyP99()) / pre.latencyP99()) * 100
                        : 0.0;
        int newErrorCount =
                (int) newErrors.clusters().stream().filter(LogCluster::isNew).count();

        return String.format(
                """
                Analyze the deployment impact for service '%s' and produce a DeployImpactReport.

                ## Deploy Details
                - Deploy ID: %s
                - Commit: %s
                - Author: %s
                - Message: %s
                - Changed Files: %s
                - Deployed At: %s
                - Previous Revision: %s

                ## Pre-Deploy Metrics
                - Error Rate: %.4f (%.2f%%)
                - Latency P50: %.1fms | P95: %.1fms | P99: %.1fms
                - Throughput: %.0f req/s
                - CPU: %.1f%% | Memory: %.1f%%

                ## Post-Deploy Metrics
                - Error Rate: %.4f (%.2f%%)
                - Latency P50: %.1fms | P95: %.1fms | P99: %.1fms
                - Throughput: %.0f req/s
                - CPU: %.1f%% | Memory: %.1f%%

                ## Metric Changes
                - Error Rate Change: %.1f%%
                - Latency P99 Change: %.1f%%

                ## New Errors After Deploy (%d clusters, %d new patterns)
                %s

                Provide your analysis as a DeployImpactReport with:
                1. isDeployCaused: whether the deployment caused the issue
                2. confidence: HIGH, MEDIUM, or LOW
                3. evidence: list of metric changes with metric name, before, after, and changePercent
                4. newErrors: list of new error summaries with exceptionType, count, and firstSeen
                5. rollbackRecommendation: ROLLBACK (clear regression), MONITOR (uncertain), or SAFE (no impact)
                6. recommendation: ONE specific actionable recommendation
                """,
                query.service(),
                detail.deployId(),
                detail.commitSha(),
                detail.author(),
                detail.commitMessage(),
                String.join(", ", detail.changedFiles()),
                detail.deployedAt(),
                detail.previousRevision(),
                pre.errorRate(),
                pre.errorRate() * 100,
                pre.latencyP50(),
                pre.latencyP95(),
                pre.latencyP99(),
                pre.throughput(),
                pre.cpuPercent(),
                pre.memoryPercent(),
                post.errorRate(),
                post.errorRate() * 100,
                post.latencyP50(),
                post.latencyP95(),
                post.latencyP99(),
                post.throughput(),
                post.cpuPercent(),
                post.memoryPercent(),
                errorRateChange,
                latencyP99Change,
                newErrors.clusters().size(),
                newErrorCount,
                formatErrors(newErrors));
    }

    private String formatErrors(NewErrorSnapshot newErrors) {
        if (newErrors.clusters().isEmpty()) {
            return "No new error clusters found after deployment.";
        }
        var sb = new StringBuilder();
        for (var cluster : newErrors.clusters()) {
            sb.append(
                    String.format(
                            "- %s (%s): %d occurrences, first: %s, last: %s%s%n",
                            cluster.exceptionType(),
                            cluster.fingerprint(),
                            cluster.count(),
                            cluster.firstSeen(),
                            cluster.lastSeen(),
                            cluster.isNew() ? " [NEW]" : ""));
        }
        return sb.toString();
    }
}
