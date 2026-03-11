package com.stablebridge.oncall.agent.triage;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.AlertHistoryData;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.AnnotationData;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.DeployData;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.FormattedTriageReport;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.LogSnapshot;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.MetricsData;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.grafana.DashboardProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.pagerduty.AlertHistoryProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.TriageReportFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.AlertFixtures.anAlertContext;
import static com.stablebridge.oncall.fixtures.AlertFixtures.anAlertHistorySnapshot;
import static com.stablebridge.oncall.fixtures.AlertFixtures.anIncidentAssessment;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeploySnapshot;
import static com.stablebridge.oncall.fixtures.LogFixtures.aLogCluster;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aMetricsSnapshot;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class IncidentTriageAgentTest {

    @Mock private LogSearchProvider logSearchProvider;
    @Mock private MetricsProvider metricsProvider;
    @Mock private DeployHistoryProvider deployHistoryProvider;
    @Mock private AlertHistoryProvider alertHistoryProvider;
    @Mock private DashboardProvider dashboardProvider;
    @Mock private TriageReportFormatter triageReportFormatter;

    private IncidentTriageAgent agent;

    @BeforeEach
    void setUp() {
        agent =
                new IncidentTriageAgent(
                        logSearchProvider,
                        metricsProvider,
                        deployHistoryProvider,
                        alertHistoryProvider,
                        dashboardProvider,
                        triageReportFormatter);
    }

    @Test
    @DisplayName("parseAlert extracts service name from simple input")
    void shouldParseServiceName() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseAlert(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.severity()).isEqualTo(IncidentSeverity.SEV2);
        assertThat(result.description()).isEqualTo("Alert triggered");
    }

    @Test
    @DisplayName("parseAlert extracts service, severity, and description from pipe-delimited input")
    void shouldParseFullInput() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " | SEV1 | High error rate detected");

        // when
        var result = agent.parseAlert(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.severity()).isEqualTo(IncidentSeverity.SEV1);
        assertThat(result.description()).isEqualTo("High error rate detected");
    }

    @Test
    @DisplayName("parseAlert trims whitespace from input")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseAlert(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("parseAlert generates alert ID and dedup key")
    void shouldGenerateAlertIdAndDedupKey() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseAlert(userInput);

        // then
        assertThat(result.alertId()).startsWith("ALT-");
        assertThat(result.dedupKey()).isEqualTo("alert-api-sev2");
        assertThat(result.triggeredAt()).isNotNull();
    }

    @Test
    @DisplayName("fetchRecentLogs returns log clusters from Loki")
    void shouldFetchRecentLogs() {
        // given
        var alert = anAlertContext();
        var clusters = List.of(aLogCluster());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(clusters);

        // when
        var result = agent.fetchRecentLogs(alert);

        // then
        assertThat(result.clusters()).hasSize(1);
        assertThat(result.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(result.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("fetchRecentLogs returns empty snapshot when no errors")
    void shouldReturnEmptyLogsWhenNone() {
        // given
        var alert = anAlertContext();
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of());

        // when
        var result = agent.fetchRecentLogs(alert);

        // then
        assertThat(result.clusters()).isEmpty();
    }

    @Test
    @DisplayName("fetchServiceMetrics returns metrics snapshot from Prometheus")
    void shouldFetchServiceMetrics() {
        // given
        var alert = anAlertContext();
        var expectedMetrics = aMetricsSnapshot();
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(expectedMetrics);

        // when
        var result = agent.fetchServiceMetrics(alert);

        // then
        assertThat(result.snapshot()).isEqualTo(expectedMetrics);
        assertThat(result.snapshot().service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchRecentDeploys returns deploy snapshot from ArgoCD")
    void shouldFetchRecentDeploys() {
        // given
        var alert = anAlertContext();
        var expectedDeploy = aDeploySnapshot();
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(expectedDeploy);

        // when
        var result = agent.fetchRecentDeploys(alert);

        // then
        assertThat(result.snapshot()).isEqualTo(expectedDeploy);
        assertThat(result.snapshot().appName()).isEqualTo(SERVICE_NAME);
        assertThat(result.snapshot().syncStatus()).isEqualTo("Synced");
        verify(deployHistoryProvider).fetchLatestDeploy(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchAlertHistory returns alert history from PagerDuty")
    void shouldFetchAlertHistory() {
        // given
        var alert = anAlertContext();
        var expectedHistory = anAlertHistorySnapshot();
        given(alertHistoryProvider.fetchAlertHistory(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(expectedHistory);

        // when
        var result = agent.fetchAlertHistory(alert);

        // then
        assertThat(result.snapshot()).isEqualTo(expectedHistory);
        assertThat(result.snapshot().totalAlerts()).isEqualTo(5);
        assertThat(result.snapshot().isRecurring()).isTrue();
    }

    @Test
    @DisplayName("fetchDashboardAnnotations returns annotations from Grafana")
    void shouldFetchDashboardAnnotations() {
        // given
        var alert = anAlertContext();
        var expectedAnnotations = List.of("Deploy v1.2.3", "Incident INC-042 resolved");
        given(dashboardProvider.fetchAnnotations(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(expectedAnnotations);

        // when
        var result = agent.fetchDashboardAnnotations(alert);

        // then
        assertThat(result.annotations()).hasSize(2);
        assertThat(result.annotations()).contains("Deploy v1.2.3");
    }

    @Test
    @DisplayName("fetchDashboardAnnotations returns empty list when no annotations")
    void shouldReturnEmptyAnnotationsWhenNone() {
        // given
        var alert = anAlertContext();
        given(dashboardProvider.fetchAnnotations(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());

        // when
        var result = agent.fetchDashboardAnnotations(alert);

        // then
        assertThat(result.annotations()).isEmpty();
    }

    @Test
    @DisplayName("formatAndNotify delegates to TriageReportFormatter and wraps result")
    void shouldFormatAndNotify() {
        // given
        var assessment = anIncidentAssessment();
        var alert = anAlertContext();
        var logs = new LogSnapshot(List.of(aLogCluster()));
        var metrics = new MetricsData(aMetricsSnapshot());
        var deploy = new DeployData(aDeploySnapshot());
        var expectedMarkdown = "# Incident Triage Report\n\nFormatted body";
        given(triageReportFormatter.format(any())).willReturn(expectedMarkdown);

        // when
        var result = agent.formatAndNotify(assessment, alert, logs, metrics, deploy);

        // then
        assertThat(result.alert()).isEqualTo(alert);
        assertThat(result.assessment()).isEqualTo(assessment);
        assertThat(result.assessment().likelyCause()).contains("NullPointerException");
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(triageReportFormatter).format(any());
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var clusters = List.of(aLogCluster());
        var metricsSnap = aMetricsSnapshot();
        var deploySnap = aDeploySnapshot();
        var historySnap = anAlertHistorySnapshot();
        var annotationList = List.of("Deploy v1.2.3");

        // when
        var logSnapshot = new LogSnapshot(clusters);
        var metricsData = new MetricsData(metricsSnap);
        var deployData = new DeployData(deploySnap);
        var alertHistoryData = new AlertHistoryData(historySnap);
        var annotationData = new AnnotationData(annotationList);
        var formattedTriageReport =
                new FormattedTriageReport(anAlertContext(), anIncidentAssessment(), "markdown");

        // then
        assertThat(logSnapshot.clusters()).isEqualTo(clusters);
        assertThat(metricsData.snapshot()).isEqualTo(metricsSnap);
        assertThat(deployData.snapshot()).isEqualTo(deploySnap);
        assertThat(alertHistoryData.snapshot()).isEqualTo(historySnap);
        assertThat(annotationData.annotations()).isEqualTo(annotationList);
        assertThat(formattedTriageReport.alert().service()).isEqualTo(SERVICE_NAME);
        assertThat(formattedTriageReport.assessment().likelyCause())
                .contains("NullPointerException");
        assertThat(formattedTriageReport.markdown()).isEqualTo("markdown");
    }
}
