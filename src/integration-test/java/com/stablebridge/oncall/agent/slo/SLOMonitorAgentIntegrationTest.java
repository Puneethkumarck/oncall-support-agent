package com.stablebridge.oncall.agent.slo;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.common.SLOStatus;
import com.stablebridge.oncall.domain.model.slo.SLOReport;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

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
import static org.mockito.BDDMockito.then;

@Import(TestJacksonConfig.class)
class SLOMonitorAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private MetricsProvider metricsProvider;
    private DeployHistoryProvider deployHistoryProvider;

    private SLOMonitorAgent agent;

    @BeforeEach
    void setUpMocks() {
        metricsProvider = Mockito.mock(MetricsProvider.class);
        deployHistoryProvider = Mockito.mock(DeployHistoryProvider.class);

        agent =
                new SLOMonitorAgent(
                        metricsProvider,
                        deployHistoryProvider,
                        new com.stablebridge.oncall.domain.service.SLOReportFormatter());
    }

    private void stubAllPorts() {
        given(metricsProvider.fetchSLOBudget(SERVICE_NAME, "availability"))
                .willReturn(anSLOSnapshot());
        given(metricsProvider.fetchBurnContributors(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of(aBurnContributor()));
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of(aDeploySnapshot()));
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput → data fetch → LLM analyze → formatted report")
    void shouldExecuteFullGoapChain() {
        // given — stub all external ports
        stubAllPorts();

        // given — stub LLM to return fixture SLO report
        whenCreateObject(prompt -> prompt.contains(SERVICE_NAME), SLOReport.class)
                .thenReturn(anSLOReport());

        // when — run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform, SLOMonitorAgent.FormattedSLOReport.class);
        var result = invocation.invoke(new UserInput(SERVICE_NAME), agent);

        // then — verify the output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report().sloStatus()).isEqualTo(SLOStatus.WARNING);
        assertThat(result.markdown()).contains("SLO Report");
        assertThat(result.markdown()).contains("WARNING");
        assertThat(result.markdown()).contains("Error Budget");
        assertThat(result.markdown()).contains("Recommendation");

        // then — verify LLM was called with correct context
        verifyCreateObject(
                prompt -> prompt.contains(SERVICE_NAME) && prompt.contains("Analyze"),
                SLOReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("All three data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query = agent.parseSLOQuery(new UserInput(SERVICE_NAME));

        // when
        agent.fetchErrorBudget(query);
        agent.fetchBurnContributors(query);
        agent.fetchRecentChanges(query);

        // then
        then(metricsProvider).should().fetchSLOBudget(SERVICE_NAME, "availability");
        then(metricsProvider)
                .should()
                .fetchBurnContributors(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class));
        then(deployHistoryProvider)
                .should()
                .fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class));
    }

    @Test
    @DisplayName("Formatted SLO report contains all expected sections")
    void shouldProduceFormattedReportWithAllSections() {
        // given
        var query = agent.parseSLOQuery(new UserInput(SERVICE_NAME));
        var report = anSLOReport();

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.markdown())
                .contains("SLO Report: " + SERVICE_NAME)
                .contains("WARNING")
                .contains("Error Budget")
                .contains("Top Burn Contributors")
                .contains("/api/v1/alerts")
                .contains("Recommendation");
    }

    @Test
    @DisplayName("Error budget action returns correct snapshot from Prometheus")
    void shouldFetchErrorBudgetFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseSLOQuery(new UserInput(SERVICE_NAME));

        // when
        var errorBudget = agent.fetchErrorBudget(query);

        // then
        assertThat(errorBudget.snapshot().sloName()).isEqualTo("availability-99.9");
        assertThat(errorBudget.snapshot().budgetRemaining()).isEqualTo(55.0);
        assertThat(errorBudget.snapshot().burnRate()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("Burn contributors action returns top contributors from Prometheus")
    void shouldFetchBurnContributorsFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseSLOQuery(new UserInput(SERVICE_NAME));

        // when
        var contributors = agent.fetchBurnContributors(query);

        // then
        assertThat(contributors.contributors()).hasSize(1);
        assertThat(contributors.contributors().getFirst().endpoint())
                .isEqualTo("/api/v1/alerts");
        assertThat(contributors.contributors().getFirst().contributionPercent())
                .isEqualTo(45.0);
    }

    @Test
    @DisplayName("Recent changes action returns deploys from ArgoCD")
    void shouldFetchRecentChangesFromArgoCD() {
        // given
        stubAllPorts();
        var query = agent.parseSLOQuery(new UserInput(SERVICE_NAME));

        // when
        var changes = agent.fetchRecentChanges(query);

        // then
        assertThat(changes.deploys()).hasSize(1);
        assertThat(changes.deploys().getFirst().appName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("Graceful handling when no burn contributors are found")
    void shouldHandleEmptyBurnContributors() {
        // given
        given(metricsProvider.fetchBurnContributors(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());
        var query = agent.parseSLOQuery(new UserInput(SERVICE_NAME));

        // when
        var contributors = agent.fetchBurnContributors(query);

        // then
        assertThat(contributors.contributors()).isEmpty();
    }

    @Test
    @DisplayName("Graceful handling when no recent deployments exist")
    void shouldHandleEmptyDeploys() {
        // given
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());
        var query = agent.parseSLOQuery(new UserInput(SERVICE_NAME));

        // when
        var changes = agent.fetchRecentChanges(query);

        // then
        assertThat(changes.deploys()).isEmpty();
    }
}
