package com.stablebridge.oncall.agent.triage;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.alert.AlertContext;
import com.stablebridge.oncall.domain.model.alert.AlertHistorySnapshot;
import com.stablebridge.oncall.domain.model.alert.IncidentAssessment;
import com.stablebridge.oncall.domain.model.alert.TriageReport;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.grafana.DashboardProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.pagerduty.AlertHistoryProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.TriageReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(
        description =
                "Auto-triage production incidents by gathering logs, metrics, deploys, alert history,"
                    + " and annotations to produce a pre-investigated incident summary")
@Slf4j
@RequiredArgsConstructor
public class IncidentTriageAgent {

    private final LogSearchProvider logSearchProvider;
    private final MetricsProvider metricsProvider;
    private final DeployHistoryProvider deployHistoryProvider;
    private final AlertHistoryProvider alertHistoryProvider;
    private final DashboardProvider dashboardProvider;
    private final TriageReportFormatter triageReportFormatter;

    // Blackboard state records
    public record LogSnapshot(List<LogCluster> clusters) {}

    public record MetricsData(MetricsSnapshot snapshot) {}

    public record DeployData(DeploySnapshot snapshot) {}

    public record AlertHistoryData(AlertHistorySnapshot snapshot) {}

    public record AnnotationData(List<String> annotations) {}

    public record FormattedTriageReport(
            AlertContext alert, IncidentAssessment assessment, String markdown) {}

    @Action(description = "Parse alert from user input into structured AlertContext")
    public AlertContext parseAlert(UserInput userInput) {
        var content = userInput.getContent().trim();
        log.info("Parsing alert from input: {}", content);

        String service;
        String severity = "SEV2";
        String description = "Alert triggered";

        var parts = content.split("\\|");
        service = parts[0].trim();
        if (parts.length > 1) {
            severity = parts[1].trim().toUpperCase();
        }
        if (parts.length > 2) {
            description = parts[2].trim();
        }

        return new AlertContext(
                "ALT-" + System.currentTimeMillis(),
                service,
                IncidentSeverity.valueOf(severity),
                description,
                Instant.now(),
                "",
                service + "-" + severity.toLowerCase());
    }

    @Action(description = "Fetch recent error logs from Loki for the alerted service")
    public LogSnapshot fetchRecentLogs(AlertContext alert) {
        log.info("Fetching recent logs for service: {}", alert.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(30));
        var clusters = logSearchProvider.searchLogs(alert.service(), from, to, "ERROR");
        return new LogSnapshot(clusters);
    }

    @Action(description = "Fetch current service metrics from Prometheus")
    public MetricsData fetchServiceMetrics(AlertContext alert) {
        log.info("Fetching service metrics for: {}", alert.service());
        var metrics = metricsProvider.fetchServiceMetrics(alert.service(), Instant.now());
        return new MetricsData(metrics);
    }

    @Action(description = "Fetch recent deploy history from ArgoCD")
    public DeployData fetchRecentDeploys(AlertContext alert) {
        log.info("Fetching recent deploys for service: {}", alert.service());
        var deploy = deployHistoryProvider.fetchLatestDeploy(alert.service());
        return new DeployData(deploy);
    }

    @Action(description = "Fetch alert history from PagerDuty to check for recurring patterns")
    public AlertHistoryData fetchAlertHistory(AlertContext alert) {
        log.info("Fetching alert history for service: {}", alert.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(24));
        var history = alertHistoryProvider.fetchAlertHistory(alert.service(), from, to);
        return new AlertHistoryData(history);
    }

    @Action(description = "Fetch dashboard annotations from Grafana for recent events")
    public AnnotationData fetchDashboardAnnotations(AlertContext alert) {
        log.info("Fetching dashboard annotations for service: {}", alert.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(1));
        var annotations = dashboardProvider.fetchAnnotations(alert.service(), from, to);
        return new AnnotationData(annotations);
    }

    @Action(
            description =
                    "Analyze all gathered data and produce incident assessment using Senior SRE"
                        + " persona")
    public IncidentAssessment triageAndAnalyze(
            AlertContext alert,
            LogSnapshot logs,
            MetricsData metrics,
            DeployData deploy,
            AlertHistoryData alertHistory,
            AnnotationData annotations,
            Ai ai) {
        log.info("Triaging and analyzing incident for service: {}", alert.service());
        String prompt =
                buildTriagePrompt(
                        alert,
                        logs,
                        metrics.snapshot(),
                        deploy.snapshot(),
                        alertHistory.snapshot(),
                        annotations.annotations());
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.SENIOR_SRE)
                .creating(IncidentAssessment.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted incident triage report",
            export =
                    @Export(
                            name = "triageIncident",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format triage report as markdown and prepare for notification")
    public FormattedTriageReport formatAndNotify(
            IncidentAssessment assessment,
            AlertContext alert,
            LogSnapshot logs,
            MetricsData metrics,
            DeployData deploy) {
        log.info(
                "Formatting triage report for service: {} — cause: {}",
                alert.service(),
                assessment.likelyCause());
        var triageReport =
                new TriageReport(
                        alert,
                        assessment,
                        metrics.snapshot(),
                        deploy.snapshot(),
                        logs.clusters(),
                        "");
        String markdown = triageReportFormatter.format(triageReport);
        return new FormattedTriageReport(alert, assessment, markdown);
    }

    private String buildTriagePrompt(
            AlertContext alert,
            LogSnapshot logs,
            MetricsSnapshot metrics,
            DeploySnapshot deploy,
            AlertHistorySnapshot alertHistory,
            List<String> annotations) {
        int newErrorCount =
                (int) logs.clusters().stream().filter(LogCluster::isNew).count();
        int totalErrorHits =
                logs.clusters().stream().mapToInt(LogCluster::count).sum();

        return String.format(
                """
                Triage the following production incident and produce an IncidentAssessment.

                ## Alert
                - Service: %s
                - Severity: %s
                - Description: %s
                - Triggered At: %s
                - Runbook: %s

                ## Recent Logs (%d clusters, %d new patterns, %d total hits)
                %s

                ## Current Metrics
                - Error Rate: %.4f (%.2f%%)
                - Latency P50: %.1fms | P95: %.1fms | P99: %.1fms
                - Throughput: %.0f req/s
                - CPU: %.1f%% | Memory: %.1f%% | Saturation: %.2f

                ## Recent Deploy
                - App: %s
                - Deploy ID: %s
                - Commit: %s
                - Author: %s
                - Deployed At: %s
                - Sync Status: %s
                - Health: %s
                - Time Since Deploy: %sm

                ## Alert History (24h)
                - Total Alerts: %d
                - Is Recurring: %s
                - Last Occurrence: %s
                - Recent Alerts: %s

                ## Dashboard Annotations
                %s

                Provide your assessment as an IncidentAssessment with:
                1. severity: the confirmed incident severity (SEV1-SEV5)
                2. blastRadius: which services/users are affected
                3. likelyCause: the SINGLE most likely root cause with specific evidence
                4. evidence: list of supporting evidence strings
                5. isDeployRelated: whether the incident correlates with a recent deployment
                6. recommendation: ONE specific actionable remediation step
                """,
                alert.service(),
                alert.severity(),
                alert.description(),
                alert.triggeredAt(),
                alert.runbookUrl(),
                logs.clusters().size(),
                newErrorCount,
                totalErrorHits,
                formatLogClusters(logs),
                metrics.errorRate(),
                metrics.errorRate() * 100,
                metrics.latencyP50(),
                metrics.latencyP95(),
                metrics.latencyP99(),
                metrics.throughput(),
                metrics.cpuPercent(),
                metrics.memoryPercent(),
                metrics.saturation(),
                deploy.appName(),
                deploy.lastDeployId(),
                deploy.commitSha(),
                deploy.author(),
                deploy.deployedAt(),
                deploy.syncStatus(),
                deploy.health(),
                deploy.timeSinceDeploy().toMinutes(),
                alertHistory.totalAlerts(),
                alertHistory.isRecurring(),
                alertHistory.lastOccurrence(),
                formatAlertHistory(alertHistory),
                formatAnnotations(annotations));
    }

    private String formatLogClusters(LogSnapshot logs) {
        if (logs.clusters().isEmpty()) {
            return "No error clusters found.";
        }
        var sb = new StringBuilder();
        for (var cluster : logs.clusters()) {
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

    private String formatAlertHistory(AlertHistorySnapshot alertHistory) {
        if (alertHistory.recentAlerts().isEmpty()) {
            return "No recent alerts.";
        }
        var sb = new StringBuilder();
        for (var alert : alertHistory.recentAlerts()) {
            sb.append(
                    String.format(
                            "  - %s: %s [%s] triggered: %s%n",
                            alert.alertId(),
                            alert.description(),
                            alert.status(),
                            alert.triggeredAt()));
        }
        return sb.toString();
    }

    private String formatAnnotations(List<String> annotations) {
        if (annotations.isEmpty()) {
            return "No recent annotations.";
        }
        var sb = new StringBuilder();
        for (var annotation : annotations) {
            sb.append("- ").append(annotation).append("\n");
        }
        return sb.toString();
    }
}
