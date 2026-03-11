package com.stablebridge.oncall.agent.logs;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.logs.LogAnalysisReport;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.LogAnalysisReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(description = "Analyze log patterns to cluster errors, identify new patterns, and separate signal from noise")
@Slf4j
@RequiredArgsConstructor
public class LogAnalysisAgent {

    private final LogSearchProvider logSearchProvider;
    private final DeployHistoryProvider deployHistoryProvider;
    private final MetricsProvider metricsProvider;
    private final LogAnalysisReportFormatter logAnalysisReportFormatter;

    // Blackboard state records
    public record LogQuery(String service, Instant from, Instant to, String severityFilter) {}

    public record RawLogSnapshot(List<LogCluster> entries, int totalHits) {}

    public record BaselineSnapshot(double normalErrorRate, double currentErrorRate) {}

    public record FormattedLogAnalysis(
            LogQuery query, LogAnalysisReport report, String formattedBody) {}

    @Action(description = "Parse log analysis request into structured query")
    public LogQuery parseLogQuery(UserInput userInput) {
        var content = userInput.getContent().trim();
        log.info("Parsing log query from input: {}", content);

        String service;
        int windowMinutes = 30;
        String severityFilter = "ERROR";

        var parts = content.split("\\s+");
        service = parts[0];
        if (parts.length > 1) {
            try {
                windowMinutes = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                log.debug("Could not parse time window '{}', using default 30m", parts[1]);
            }
        }
        if (parts.length > 2) {
            severityFilter = parts[2].toUpperCase();
        }

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(windowMinutes));
        return new LogQuery(service, from, to, severityFilter);
    }

    @Action(description = "Fetch error logs from Loki for the given service and time window")
    public RawLogSnapshot fetchErrorLogs(LogQuery query) {
        log.info(
                "Fetching error logs for service: {} from {} to {}",
                query.service(),
                query.from(),
                query.to());
        var clusters =
                logSearchProvider.searchLogs(
                        query.service(), query.from(), query.to(), query.severityFilter());
        int totalHits = clusters.stream().mapToInt(LogCluster::count).sum();
        return new RawLogSnapshot(clusters, totalHits);
    }

    @Action(description = "Fetch recent deploy history from ArgoCD for the analysis window")
    public DeploySnapshot fetchDeployHistory(LogQuery query) {
        log.info("Fetching deploy history for service: {}", query.service());
        return deployHistoryProvider.fetchLatestDeploy(query.service());
    }

    @Action(description = "Fetch baseline metrics from Prometheus to compare error rates")
    public BaselineSnapshot fetchBaselineMetrics(LogQuery query) {
        log.info("Fetching baseline metrics for service: {}", query.service());
        Instant baselineEnd = query.from();
        Instant baselineStart = baselineEnd.minus(Duration.ofHours(24));
        MetricsSnapshot baseline =
                metricsProvider.fetchServiceMetrics(query.service(), baselineStart);
        MetricsSnapshot current =
                metricsProvider.fetchServiceMetrics(query.service(), query.to());
        return new BaselineSnapshot(baseline.errorRate(), current.errorRate());
    }

    @Action(
            description =
                    "Cluster error patterns and analyze logs using Log Analyst persona")
    public LogAnalysisReport clusterAndAnalyze(
            RawLogSnapshot rawLogs,
            DeploySnapshot deploySnapshot,
            BaselineSnapshot baseline,
            LogQuery query,
            Ai ai) {
        log.info("Clustering and analyzing logs for service: {}", query.service());
        String prompt = buildAnalysisPrompt(query, rawLogs, deploySnapshot, baseline);
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.LOG_ANALYST)
                .creating(LogAnalysisReport.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted log analysis report",
            export =
                    @Export(
                            name = "analyzeLogPatterns",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format log analysis report as markdown")
    public FormattedLogAnalysis formatReport(LogAnalysisReport report, LogQuery query) {
        log.info(
                "Formatting log analysis report for service: {} — {} clusters, {} new patterns",
                query.service(),
                report.clusters().size(),
                report.newPatterns().size());
        String markdown = logAnalysisReportFormatter.format(query.service(), report);
        return new FormattedLogAnalysis(query, report, markdown);
    }

    private String buildAnalysisPrompt(
            LogQuery query,
            RawLogSnapshot rawLogs,
            DeploySnapshot deploySnapshot,
            BaselineSnapshot baseline) {
        int newClusterCount =
                (int) rawLogs.entries().stream().filter(LogCluster::isNew).count();

        return String.format(
                """
                Analyze the error logs for service '%s' and produce a LogAnalysisReport.

                ## Time Window
                - From: %s
                - To: %s
                - Severity Filter: %s

                ## Error Summary
                - Total Hits: %d
                - Error Clusters: %d (%d new patterns)
                %s

                ## Baseline Comparison
                - Normal Error Rate (24h baseline): %.4f (%.2f%%)
                - Current Error Rate: %.4f (%.2f%%)
                - Change: %.1fx %s

                ## Recent Deploy
                - App: %s
                - Deploy ID: %s
                - Commit: %s
                - Author: %s
                - Deployed At: %s
                - Sync Status: %s
                - Health: %s
                - Time Since Deploy: %s

                Provide your analysis as a LogAnalysisReport with:
                1. clusters: list of error clusters grouped by exception type with fingerprint, count, firstSeen, lastSeen, sampleStackTrace, and isNew flag
                2. newPatterns: list of newly detected error patterns with pattern description, confidence (0-1), and possibleCause
                3. deployCorrelation: whether errors correlate with the recent deploy (isCorrelated, deployId, timeDelta)
                4. recommendation: ONE specific actionable recommendation
                """,
                query.service(),
                query.from(),
                query.to(),
                query.severityFilter(),
                rawLogs.totalHits(),
                rawLogs.entries().size(),
                newClusterCount,
                formatClusters(rawLogs),
                baseline.normalErrorRate(),
                baseline.normalErrorRate() * 100,
                baseline.currentErrorRate(),
                baseline.currentErrorRate() * 100,
                baseline.currentErrorRate() > 0 && baseline.normalErrorRate() > 0
                        ? baseline.currentErrorRate() / baseline.normalErrorRate()
                        : 0.0,
                baseline.currentErrorRate() > baseline.normalErrorRate()
                        ? "INCREASE"
                        : "STABLE/DECREASE",
                deploySnapshot.appName(),
                deploySnapshot.lastDeployId(),
                deploySnapshot.commitSha(),
                deploySnapshot.author(),
                deploySnapshot.deployedAt(),
                deploySnapshot.syncStatus(),
                deploySnapshot.health(),
                deploySnapshot.timeSinceDeploy().toMinutes() + "m");
    }

    private String formatClusters(RawLogSnapshot rawLogs) {
        if (rawLogs.entries().isEmpty()) {
            return "No error clusters found.";
        }
        var sb = new StringBuilder();
        for (var cluster : rawLogs.entries()) {
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
