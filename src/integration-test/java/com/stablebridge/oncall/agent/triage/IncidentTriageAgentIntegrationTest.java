package com.stablebridge.oncall.agent.triage;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.alert.IncidentAssessment;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.grafana.DashboardProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.pagerduty.AlertHistoryProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

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
import static org.mockito.BDDMockito.then;

@Import(TestJacksonConfig.class)
class IncidentTriageAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private LogSearchProvider logSearchProvider;
    private MetricsProvider metricsProvider;
    private DeployHistoryProvider deployHistoryProvider;
    private AlertHistoryProvider alertHistoryProvider;
    private DashboardProvider dashboardProvider;

    private IncidentTriageAgent agent;

    @BeforeEach
    void setUpMocks() {
        logSearchProvider = Mockito.mock(LogSearchProvider.class);
        metricsProvider = Mockito.mock(MetricsProvider.class);
        deployHistoryProvider = Mockito.mock(DeployHistoryProvider.class);
        alertHistoryProvider = Mockito.mock(AlertHistoryProvider.class);
        dashboardProvider = Mockito.mock(DashboardProvider.class);

        agent =
                new IncidentTriageAgent(
                        logSearchProvider,
                        metricsProvider,
                        deployHistoryProvider,
                        alertHistoryProvider,
                        dashboardProvider,
                        new com.stablebridge.oncall.domain.service.TriageReportFormatter());
    }

    private void stubAllPorts() {
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of(aLogCluster()));
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(aDeploySnapshot());
        given(alertHistoryProvider.fetchAlertHistory(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(anAlertHistorySnapshot());
        given(dashboardProvider.fetchAnnotations(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of("Deploy v1.2.3", "Incident INC-042 resolved"));
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput -> data fetch -> LLM triage -> formatted report")
    void shouldExecuteFullGoapChain() {
        // given — stub all external ports
        stubAllPorts();

        // given — stub LLM to return fixture incident assessment
        whenCreateObject(
                        prompt -> prompt.contains(SERVICE_NAME), IncidentAssessment.class)
                .thenReturn(anIncidentAssessment());

        // when — run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform, IncidentTriageAgent.FormattedTriageReport.class);
        var result = invocation.invoke(new UserInput(SERVICE_NAME), agent);

        // then — verify the output
        assertThat(result).isNotNull();
        assertThat(result.alert().service()).isEqualTo(SERVICE_NAME);
        assertThat(result.assessment().likelyCause()).contains("NullPointerException");
        assertThat(result.assessment().isDeployRelated()).isTrue();
        assertThat(result.markdown()).contains("Incident Triage Report");
        assertThat(result.markdown()).contains(SERVICE_NAME);
        assertThat(result.markdown()).contains("Recommendation");

        // then — verify LLM was called with correct context
        verifyCreateObject(
                prompt -> prompt.contains(SERVICE_NAME) && prompt.contains("Triage"),
                IncidentAssessment.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("All five data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        agent.fetchRecentLogs(alert);
        agent.fetchServiceMetrics(alert);
        agent.fetchRecentDeploys(alert);
        agent.fetchAlertHistory(alert);
        agent.fetchDashboardAnnotations(alert);

        // then
        then(logSearchProvider)
                .should()
                .searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR"));
        then(metricsProvider)
                .should()
                .fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class));
        then(deployHistoryProvider).should().fetchLatestDeploy(SERVICE_NAME);
        then(alertHistoryProvider)
                .should()
                .fetchAlertHistory(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class));
        then(dashboardProvider)
                .should()
                .fetchAnnotations(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class));
    }

    @Test
    @DisplayName("Formatted triage report contains all expected sections")
    void shouldProduceFormattedReportWithAllSections() {
        // given
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));
        var assessment = anIncidentAssessment();
        var logs = new IncidentTriageAgent.LogSnapshot(List.of(aLogCluster()));
        var metrics = new IncidentTriageAgent.MetricsData(aMetricsSnapshot());
        var deploy = new IncidentTriageAgent.DeployData(aDeploySnapshot());

        // when
        var result = agent.formatAndNotify(assessment, alert, logs, metrics, deploy);

        // then
        assertThat(result.alert().service()).isEqualTo(SERVICE_NAME);
        assertThat(result.assessment()).isEqualTo(assessment);
        assertThat(result.markdown())
                .contains("Incident Triage Report")
                .contains(SERVICE_NAME)
                .contains("Assessment")
                .contains("NullPointerException")
                .contains("Recommendation");
    }

    @Test
    @DisplayName("Recent logs action returns clusters from Loki")
    void shouldFetchRecentLogsFromLoki() {
        // given
        stubAllPorts();
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        var logs = agent.fetchRecentLogs(alert);

        // then
        assertThat(logs.clusters()).hasSize(1);
        assertThat(logs.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(logs.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("Service metrics action returns snapshot from Prometheus")
    void shouldFetchServiceMetricsFromPrometheus() {
        // given
        stubAllPorts();
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        var metrics = agent.fetchServiceMetrics(alert);

        // then
        assertThat(metrics.snapshot().service()).isEqualTo(SERVICE_NAME);
        assertThat(metrics.snapshot().errorRate()).isEqualTo(0.05);
        assertThat(metrics.snapshot().latencyP99()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("Recent deploys action returns snapshot from ArgoCD")
    void shouldFetchRecentDeploysFromArgoCD() {
        // given
        stubAllPorts();
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        var deploy = agent.fetchRecentDeploys(alert);

        // then
        assertThat(deploy.snapshot().appName()).isEqualTo(SERVICE_NAME);
        assertThat(deploy.snapshot().syncStatus()).isEqualTo("Synced");
        assertThat(deploy.snapshot().health()).isEqualTo("Healthy");
    }

    @Test
    @DisplayName("Alert history action returns history from PagerDuty")
    void shouldFetchAlertHistoryFromPagerDuty() {
        // given
        stubAllPorts();
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        var history = agent.fetchAlertHistory(alert);

        // then
        assertThat(history.snapshot().totalAlerts()).isEqualTo(5);
        assertThat(history.snapshot().isRecurring()).isTrue();
        assertThat(history.snapshot().recentAlerts()).hasSize(1);
    }

    @Test
    @DisplayName("Dashboard annotations action returns annotations from Grafana")
    void shouldFetchDashboardAnnotationsFromGrafana() {
        // given
        stubAllPorts();
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        var annotations = agent.fetchDashboardAnnotations(alert);

        // then
        assertThat(annotations.annotations()).hasSize(2);
        assertThat(annotations.annotations()).contains("Deploy v1.2.3");
    }

    @Test
    @DisplayName("Graceful handling when no error logs exist")
    void shouldHandleEmptyErrorLogs() {
        // given
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of());
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        var logs = agent.fetchRecentLogs(alert);

        // then
        assertThat(logs.clusters()).isEmpty();
    }

    @Test
    @DisplayName("Graceful handling when no dashboard annotations exist")
    void shouldHandleEmptyAnnotations() {
        // given
        given(dashboardProvider.fetchAnnotations(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());
        var alert = agent.parseAlert(new UserInput(SERVICE_NAME));

        // when
        var annotations = agent.fetchDashboardAnnotations(alert);

        // then
        assertThat(annotations.annotations()).isEmpty();
    }

    @Test
    @DisplayName("Alert parsing with custom severity")
    void shouldParseAlertWithCustomSeverity() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " | SEV1 | Critical outage");

        // when
        var alert = agent.parseAlert(userInput);

        // then
        assertThat(alert.service()).isEqualTo(SERVICE_NAME);
        assertThat(alert.severity())
                .isEqualTo(com.stablebridge.oncall.domain.model.common.IncidentSeverity.SEV1);
        assertThat(alert.description()).isEqualTo("Critical outage");
    }
}
