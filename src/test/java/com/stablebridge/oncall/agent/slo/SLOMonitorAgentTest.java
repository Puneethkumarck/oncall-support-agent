package com.stablebridge.oncall.agent.slo;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.BurnContributors;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.ChangeSnapshot;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.ErrorBudgetSnapshot;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.SLOQuery;
import com.stablebridge.oncall.domain.model.common.SLOStatus;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.SLOReportFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeploySnapshot;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aBurnContributor;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.anSLOReport;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.anSLOSnapshot;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SLOMonitorAgentTest {

    @Mock private MetricsProvider metricsProvider;
    @Mock private DeployHistoryProvider deployHistoryProvider;
    @Mock private SLOReportFormatter sloReportFormatter;

    private SLOMonitorAgent agent;

    @BeforeEach
    void setUp() {
        agent = new SLOMonitorAgent(metricsProvider, deployHistoryProvider, sloReportFormatter);
    }

    @Test
    @DisplayName("parseSLOQuery extracts service name from UserInput")
    void shouldParseSLOQuery() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseSLOQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("parseSLOQuery trims whitespace from service name")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseSLOQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchErrorBudget returns SLO snapshot from Prometheus")
    void shouldFetchErrorBudget() {
        // given
        var query = new SLOQuery(SERVICE_NAME);
        var expectedSnapshot = anSLOSnapshot();
        given(metricsProvider.fetchSLOBudget(SERVICE_NAME, "availability"))
                .willReturn(expectedSnapshot);

        // when
        var result = agent.fetchErrorBudget(query);

        // then
        assertThat(result.snapshot()).isEqualTo(expectedSnapshot);
        assertThat(result.snapshot().burnRate()).isEqualTo(2.5);
        assertThat(result.snapshot().budgetRemaining()).isEqualTo(55.0);
    }

    @Test
    @DisplayName("fetchBurnContributors returns contributors from Prometheus")
    void shouldFetchBurnContributors() {
        // given
        var query = new SLOQuery(SERVICE_NAME);
        var expectedContributors = List.of(aBurnContributor());
        given(metricsProvider.fetchBurnContributors(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(expectedContributors);

        // when
        var result = agent.fetchBurnContributors(query);

        // then
        assertThat(result.contributors()).hasSize(1);
        assertThat(result.contributors().getFirst().endpoint()).isEqualTo("/api/v1/alerts");
        assertThat(result.contributors().getFirst().contributionPercent()).isEqualTo(45.0);
    }

    @Test
    @DisplayName("fetchBurnContributors returns empty list when no contributors")
    void shouldReturnEmptyContributorsWhenNone() {
        // given
        var query = new SLOQuery(SERVICE_NAME);
        given(metricsProvider.fetchBurnContributors(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());

        // when
        var result = agent.fetchBurnContributors(query);

        // then
        assertThat(result.contributors()).isEmpty();
    }

    @Test
    @DisplayName("fetchRecentChanges returns deploy snapshots from ArgoCD")
    void shouldFetchRecentChanges() {
        // given
        var query = new SLOQuery(SERVICE_NAME);
        var expectedDeploys = List.of(aDeploySnapshot());
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(expectedDeploys);

        // when
        var result = agent.fetchRecentChanges(query);

        // then
        assertThat(result.deploys()).hasSize(1);
        assertThat(result.deploys().getFirst().appName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchRecentChanges returns empty list when no recent deploys")
    void shouldReturnEmptyChangesWhenNone() {
        // given
        var query = new SLOQuery(SERVICE_NAME);
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());

        // when
        var result = agent.fetchRecentChanges(query);

        // then
        assertThat(result.deploys()).isEmpty();
    }

    @Test
    @DisplayName("formatReport delegates to SLOReportFormatter and wraps result")
    void shouldFormatReport() {
        // given
        var report = anSLOReport();
        var query = new SLOQuery(SERVICE_NAME);
        var expectedMarkdown = "# SLO Report: alert-api [WARNING]";
        given(sloReportFormatter.format(SERVICE_NAME, report)).willReturn(expectedMarkdown);

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.report().sloStatus()).isEqualTo(SLOStatus.WARNING);
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(sloReportFormatter).format(SERVICE_NAME, report);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var snapshot = anSLOSnapshot();
        var contributors = List.of(aBurnContributor());
        var deploys = List.of(aDeploySnapshot());

        // when
        var errorBudget = new ErrorBudgetSnapshot(snapshot);
        var burnContributors = new BurnContributors(contributors);
        var changeSnapshot = new ChangeSnapshot(deploys);

        // then
        assertThat(errorBudget.snapshot()).isEqualTo(snapshot);
        assertThat(burnContributors.contributors()).isEqualTo(contributors);
        assertThat(changeSnapshot.deploys()).isEqualTo(deploys);
    }
}
