package com.stablebridge.oncall.agent.deploy;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.DeployDetailData;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.DeployImpactQuery;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.NewErrorSnapshot;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.PostDeployMetrics;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.PreDeployMetrics;
import com.stablebridge.oncall.domain.model.common.RollbackDecision;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.DeployImpactReportFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeployDetail;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeployImpactReport;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeploySnapshot;
import static com.stablebridge.oncall.fixtures.LogFixtures.aLogCluster;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aMetricsSnapshot;
import static com.stablebridge.oncall.fixtures.TestConstants.DEPLOY_ID;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeployImpactAgentTest {

    @Mock private DeployHistoryProvider deployHistoryProvider;
    @Mock private MetricsProvider metricsProvider;
    @Mock private LogSearchProvider logSearchProvider;
    @Mock private DeployImpactReportFormatter deployImpactReportFormatter;

    private DeployImpactAgent agent;

    @BeforeEach
    void setUp() {
        agent =
                new DeployImpactAgent(
                        deployHistoryProvider,
                        metricsProvider,
                        logSearchProvider,
                        deployImpactReportFormatter);
    }

    @Test
    @DisplayName("parseDeployQuery extracts service name from UserInput")
    void shouldParseDeployQuery() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseDeployQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.lookbackMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("parseDeployQuery extracts service and custom lookback window")
    void shouldParseDeployQueryWithLookback() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " 60");

        // when
        var result = agent.parseDeployQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.lookbackMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("parseDeployQuery trims whitespace from input")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseDeployQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("parseDeployQuery uses default lookback when non-numeric provided")
    void shouldUseDefaultLookbackForNonNumeric() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " abc");

        // when
        var result = agent.parseDeployQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.lookbackMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("fetchDeployDetail returns deploy detail from ArgoCD")
    void shouldFetchDeployDetail() {
        // given
        var query = new DeployImpactQuery(SERVICE_NAME, 30);
        var snapshot = aDeploySnapshot();
        var detail = aDeployDetail();
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME)).willReturn(snapshot);
        given(deployHistoryProvider.fetchDeployDetail(SERVICE_NAME, DEPLOY_ID))
                .willReturn(detail);

        // when
        var result = agent.fetchDeployDetail(query);

        // then
        assertThat(result.detail()).isEqualTo(detail);
        assertThat(result.detail().deployId()).isEqualTo(DEPLOY_ID);
        assertThat(result.detail().changedFiles()).hasSize(2);
    }

    @Test
    @DisplayName("fetchPreDeployMetrics returns metrics from before deployment")
    void shouldFetchPreDeployMetrics() {
        // given
        var query = new DeployImpactQuery(SERVICE_NAME, 30);
        var snapshot = aDeploySnapshot();
        var metrics = aMetricsSnapshot();
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME)).willReturn(snapshot);
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(metrics);

        // when
        var result = agent.fetchPreDeployMetrics(query);

        // then
        assertThat(result.snapshot()).isEqualTo(metrics);
        assertThat(result.snapshot().service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchPostDeployMetrics returns current metrics from Prometheus")
    void shouldFetchPostDeployMetrics() {
        // given
        var query = new DeployImpactQuery(SERVICE_NAME, 30);
        var metrics = aMetricsSnapshot();
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(metrics);

        // when
        var result = agent.fetchPostDeployMetrics(query);

        // then
        assertThat(result.snapshot()).isEqualTo(metrics);
        assertThat(result.snapshot().errorRate()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("fetchNewErrors returns error clusters from Loki after deployment")
    void shouldFetchNewErrors() {
        // given
        var query = new DeployImpactQuery(SERVICE_NAME, 30);
        var snapshot = aDeploySnapshot();
        var clusters = List.of(aLogCluster());
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME)).willReturn(snapshot);
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(clusters);

        // when
        var result = agent.fetchNewErrors(query);

        // then
        assertThat(result.clusters()).hasSize(1);
        assertThat(result.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(result.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("fetchNewErrors returns empty list when no errors")
    void shouldReturnEmptyErrorsWhenNone() {
        // given
        var query = new DeployImpactQuery(SERVICE_NAME, 30);
        var snapshot = aDeploySnapshot();
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME)).willReturn(snapshot);
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of());

        // when
        var result = agent.fetchNewErrors(query);

        // then
        assertThat(result.clusters()).isEmpty();
    }

    @Test
    @DisplayName("formatReport delegates to DeployImpactReportFormatter and wraps result")
    void shouldFormatReport() {
        // given
        var report = aDeployImpactReport();
        var query = new DeployImpactQuery(SERVICE_NAME, 30);
        var expectedMarkdown = "# Deploy Impact: alert-api [ROLLBACK]";
        given(deployImpactReportFormatter.format(SERVICE_NAME, report))
                .willReturn(expectedMarkdown);

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.report().isDeployCaused()).isTrue();
        assertThat(result.report().rollbackRecommendation())
                .isEqualTo(RollbackDecision.ROLLBACK);
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(deployImpactReportFormatter).format(SERVICE_NAME, report);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var detail = aDeployDetail();
        var metrics = aMetricsSnapshot();
        var clusters = List.of(aLogCluster());

        // when
        var deployDetailData = new DeployDetailData(detail);
        var preDeployMetrics = new PreDeployMetrics(metrics);
        var postDeployMetrics = new PostDeployMetrics(metrics);
        var newErrorSnapshot = new NewErrorSnapshot(clusters);

        // then
        assertThat(deployDetailData.detail()).isEqualTo(detail);
        assertThat(preDeployMetrics.snapshot()).isEqualTo(metrics);
        assertThat(postDeployMetrics.snapshot()).isEqualTo(metrics);
        assertThat(newErrorSnapshot.clusters()).isEqualTo(clusters);
    }
}
