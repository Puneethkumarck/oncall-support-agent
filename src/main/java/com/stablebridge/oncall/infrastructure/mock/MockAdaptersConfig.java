package com.stablebridge.oncall.infrastructure.mock;

import com.stablebridge.oncall.domain.model.alert.AlertContext;
import com.stablebridge.oncall.domain.model.alert.AlertHistorySnapshot;
import com.stablebridge.oncall.domain.model.alert.AlertSummary;
import com.stablebridge.oncall.domain.model.common.AlertStatus;
import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.model.common.Trend;
import com.stablebridge.oncall.domain.model.deploy.DeployDetail;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.deploy.RollbackHistory;
import com.stablebridge.oncall.domain.model.deploy.RollbackResult;
import com.stablebridge.oncall.domain.model.health.DependencyStatus;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.model.metrics.MetricsWindow;
import com.stablebridge.oncall.domain.model.metrics.SLOSnapshot;
import com.stablebridge.oncall.domain.model.postmortem.TimelineEntry;
import com.stablebridge.oncall.domain.model.slo.BurnContributor;
import com.stablebridge.oncall.domain.model.trace.CallChainStep;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.argocd.DeployRollbackProvider;
import com.stablebridge.oncall.domain.port.grafana.DashboardProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.notification.SlackNotifier;
import com.stablebridge.oncall.domain.port.pagerduty.AlertDatasetProvider;
import com.stablebridge.oncall.domain.port.pagerduty.AlertHistoryProvider;
import com.stablebridge.oncall.domain.port.pagerduty.AlertNotifier;
import com.stablebridge.oncall.domain.port.pagerduty.AlertProvider;
import com.stablebridge.oncall.domain.port.pagerduty.IncidentTimelineProvider;
import com.stablebridge.oncall.domain.port.prometheus.DependencyGraphProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.port.tempo.TraceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
@Configuration
class MockAdaptersConfig {

    @Bean
    @ConditionalOnMissingBean
    LogSearchProvider mockLogSearchProvider() {
        log.warn("Using mock LogSearchProvider — no live Loki configured");
        return (service, from, to, severityFilter) -> List.of(
                new LogCluster(
                        "NullPointerException",
                        "NPE-" + service + "-001",
                        42,
                        Instant.now().minusSeconds(900),
                        Instant.now().minusSeconds(60),
                        "java.lang.NullPointerException\n\tat"
                                + " com.example.Service.process(Service.java:87)",
                        true),
                new LogCluster(
                        "SocketTimeoutException",
                        "STE-" + service + "-001",
                        12,
                        Instant.now().minusSeconds(3600),
                        Instant.now().minusSeconds(120),
                        "java.net.SocketTimeoutException: connect timed out",
                        false));
    }

    @Bean
    @ConditionalOnMissingBean
    MetricsProvider mockMetricsProvider() {
        log.warn("Using mock MetricsProvider — no live Prometheus configured");
        return new MetricsProvider() {
            @Override
            public MetricsSnapshot fetchServiceMetrics(String service, Instant at) {
                return new MetricsSnapshot(
                        service, 0.05, 12.0, 45.0, 120.0, 1500.0, 35.0, 60.0, 0.4, at);
            }

            @Override
            public MetricsWindow fetchMetricsWindow(String service, Instant from, Instant to) {
                return new MetricsWindow(from, to, fetchServiceMetrics(service, Instant.now()));
            }

            @Override
            public SLOSnapshot fetchSLOBudget(String service, String sloName) {
                return new SLOSnapshot(
                        sloName, 100.0, 45.0, 55.0, 2.5, Instant.now().plusSeconds(86400));
            }

            @Override
            public List<BurnContributor> fetchBurnContributors(
                    String service, Instant from, Instant to) {
                return List.of(
                        new BurnContributor("/api/v1/alerts", "TimeoutException", 45.0),
                        new BurnContributor("/api/v1/prices", "NullPointerException", 30.0));
            }

            @Override
            public List<SLOSnapshot> fetchBurnHistory(
                    String service, Instant from, Instant to) {
                return List.of(
                        new SLOSnapshot(
                                "availability-99.9",
                                100.0,
                                40.0,
                                60.0,
                                2.0,
                                Instant.now().plusSeconds(172800)),
                        new SLOSnapshot(
                                "availability-99.9",
                                100.0,
                                45.0,
                                55.0,
                                2.5,
                                Instant.now().plusSeconds(86400)));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    DependencyGraphProvider mockDependencyGraphProvider() {
        log.warn("Using mock DependencyGraphProvider — no live Prometheus configured");
        return service -> List.of(
                new DependencyStatus("evaluator", HealthStatus.GREEN, 25.0, Trend.STABLE),
                new DependencyStatus(
                        "notification-persister", HealthStatus.GREEN, 45.0, Trend.STABLE),
                new DependencyStatus("postgresql", HealthStatus.GREEN, 8.0, Trend.STABLE));
    }

    @Bean
    @ConditionalOnMissingBean
    TraceProvider mockTraceProvider() {
        log.warn("Using mock TraceProvider — no live Tempo configured");
        return new TraceProvider() {
            @Override
            public List<CallChainStep> fetchTrace(String traceId) {
                return List.of(
                        new CallChainStep("alert-api", "POST /api/v1/alerts", 150, "OK", false),
                        new CallChainStep("evaluator", "evaluate", 2500, "TIMEOUT", true),
                        new CallChainStep(
                                "notification-persister", "persist", 45, "OK", false));
            }

            @Override
            public List<CallChainStep> searchTraces(
                    String service, Instant from, Instant to, int limit) {
                return List.of(
                        new CallChainStep(
                                service, "POST /api/v1/alerts", 150, "OK", false),
                        new CallChainStep(
                                service, "GET /api/v1/alerts", 25, "OK", false));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    DashboardProvider mockDashboardProvider() {
        log.warn("Using mock DashboardProvider — no live Grafana configured");
        return (dashboardUid, from, to) -> List.of(
                "Deploy abc123 by user@example.com",
                "SLO budget warning — 20% remaining");
    }

    @Bean
    @ConditionalOnMissingBean
    AlertProvider mockAlertProvider() {
        log.warn("Using mock AlertProvider — no live PagerDuty configured");
        return alertId -> new AlertContext(
                alertId,
                "alert-api",
                IncidentSeverity.SEV2,
                "High error rate on alert-api",
                Instant.now().minusSeconds(300),
                "https://runbooks.example.com/alert-api/high-error-rate",
                "alert-api-high-error-rate");
    }

    @Bean
    @ConditionalOnMissingBean
    AlertHistoryProvider mockAlertHistoryProvider() {
        log.warn("Using mock AlertHistoryProvider — no live PagerDuty configured");
        return (service, from, to) -> new AlertHistorySnapshot(
                service,
                5,
                List.of(
                        new AlertSummary(
                                "ALT-001",
                                "High error rate",
                                AlertStatus.RESOLVED,
                                Instant.now().minusSeconds(3600),
                                Instant.now().minusSeconds(2700),
                                Duration.ofMinutes(15))),
                true,
                Instant.now().minusSeconds(86400));
    }

    @Bean
    @ConditionalOnMissingBean
    AlertNotifier mockAlertNotifier() {
        log.warn("Using mock AlertNotifier — no live PagerDuty configured");
        return (incidentId, note) -> log.info("Mock: would add note to {}: {}", incidentId, note);
    }

    @Bean
    @ConditionalOnMissingBean
    AlertDatasetProvider mockAlertDatasetProvider() {
        log.warn("Using mock AlertDatasetProvider — no live PagerDuty configured");
        return new AlertDatasetProvider() {
            @Override
            public List<AlertSummary> fetchAllAlerts(String team, Instant from, Instant to) {
                return List.of(
                        new AlertSummary(
                                "ALT-001",
                                "High error rate",
                                AlertStatus.RESOLVED,
                                Instant.now().minusSeconds(3600),
                                Instant.now().minusSeconds(2700),
                                Duration.ofMinutes(15)),
                        new AlertSummary(
                                "ALT-002",
                                "CPU spike",
                                AlertStatus.RESOLVED,
                                Instant.now().minusSeconds(7200),
                                Instant.now().minusSeconds(6600),
                                Duration.ofMinutes(10)));
            }

            @Override
            public List<AlertSummary> fetchAlertOutcomes(String team, Instant from, Instant to) {
                return fetchAllAlerts(team, from, to);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    IncidentTimelineProvider mockIncidentTimelineProvider() {
        log.warn("Using mock IncidentTimelineProvider — no live PagerDuty configured");
        return incidentId -> List.of(
                new TimelineEntry(
                        Instant.now().minusSeconds(3600), "Incident triggered", "PagerDuty"),
                new TimelineEntry(
                        Instant.now().minusSeconds(3300), "Acknowledged", "oncall-engineer"),
                new TimelineEntry(
                        Instant.now().minusSeconds(2700), "Resolved", "oncall-engineer"));
    }

    @Bean
    @ConditionalOnMissingBean
    DeployHistoryProvider mockDeployHistoryProvider() {
        log.warn("Using mock DeployHistoryProvider — no live ArgoCD configured");
        return new DeployHistoryProvider() {
            @Override
            public DeploySnapshot fetchLatestDeploy(String appName) {
                return new DeploySnapshot(
                        appName,
                        "deploy-abc123",
                        "abc123def456",
                        "developer@example.com",
                        Instant.now().minusSeconds(1800),
                        "Synced",
                        "Healthy",
                        Duration.ofMinutes(30),
                        List.of(appName + ":v1.2.3"));
            }

            @Override
            public DeployDetail fetchDeployDetail(String appName, String revision) {
                return new DeployDetail(
                        revision,
                        "abc123def456",
                        "developer@example.com",
                        "feat: add price threshold validation",
                        "diff --git a/...",
                        List.of("PriceEvaluationService.java", "AlertConfig.java"),
                        Instant.now().minusSeconds(1800),
                        "deploy-prev-789");
            }

            @Override
            public RollbackHistory fetchRollbackHistory(String appName) {
                return new RollbackHistory(
                        List.of("deploy-prev-789", "deploy-prev-456"),
                        true,
                        "deploy-abc123",
                        "deploy-prev-789");
            }

            @Override
            public List<DeploySnapshot> fetchDeploysInWindow(
                    String appName, Instant from, Instant to) {
                return List.of(fetchLatestDeploy(appName));
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    DeployRollbackProvider mockDeployRollbackProvider() {
        log.warn("Using mock DeployRollbackProvider — no live ArgoCD configured");
        return (appName, targetRevision) -> {
            log.info("Mock: would rollback {} to revision {}", appName, targetRevision);
            return new RollbackResult(
                    appName,
                    true,
                    "deploy-abc123",
                    targetRevision,
                    Instant.now(),
                    "Mock rollback to revision " + targetRevision + " completed");
        };
    }

    @Bean
    @ConditionalOnMissingBean
    SlackNotifier mockSlackNotifier() {
        log.warn("Using mock SlackNotifier — no live Slack configured");
        return (channel, message) -> log.info("Mock: would send to {}: {}", channel, message);
    }
}
