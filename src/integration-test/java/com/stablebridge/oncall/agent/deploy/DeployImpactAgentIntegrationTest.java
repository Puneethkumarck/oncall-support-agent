package com.stablebridge.oncall.agent.deploy;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.common.Confidence;
import com.stablebridge.oncall.domain.model.common.RollbackDecision;
import com.stablebridge.oncall.domain.model.deploy.DeployImpactReport;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeastOnce;

@Import(TestJacksonConfig.class)
class DeployImpactAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private DeployHistoryProvider deployHistoryProvider;
    private MetricsProvider metricsProvider;
    private LogSearchProvider logSearchProvider;

    private DeployImpactAgent agent;

    @BeforeEach
    void setUpMocks() {
        deployHistoryProvider = Mockito.mock(DeployHistoryProvider.class);
        metricsProvider = Mockito.mock(MetricsProvider.class);
        logSearchProvider = Mockito.mock(LogSearchProvider.class);

        agent =
                new DeployImpactAgent(
                        deployHistoryProvider,
                        metricsProvider,
                        logSearchProvider,
                        new com.stablebridge.oncall.domain.service
                                .DeployImpactReportFormatter());
    }

    private void stubAllPorts() {
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(aDeploySnapshot());
        given(deployHistoryProvider.fetchDeployDetail(SERVICE_NAME, DEPLOY_ID))
                .willReturn(aDeployDetail());
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of(aLogCluster()));
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput -> data fetch -> LLM analyze -> formatted report")
    void shouldExecuteFullGoapChain() {
        // given — stub all external ports
        stubAllPorts();

        // given — stub LLM to return fixture deploy impact report
        whenCreateObject(
                        prompt -> prompt.contains(SERVICE_NAME),
                        DeployImpactReport.class)
                .thenReturn(aDeployImpactReport());

        // when — run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform, DeployImpactAgent.FormattedDeployImpact.class);
        var result = invocation.invoke(new UserInput(SERVICE_NAME), agent);

        // then — verify the output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report().isDeployCaused()).isTrue();
        assertThat(result.report().confidence()).isEqualTo(Confidence.HIGH);
        assertThat(result.report().rollbackRecommendation())
                .isEqualTo(RollbackDecision.ROLLBACK);
        assertThat(result.markdown()).contains("Deploy Impact");
        assertThat(result.markdown()).contains("ROLLBACK");
        assertThat(result.markdown()).contains("Metric Changes");
        assertThat(result.markdown()).contains("New Errors");
        assertThat(result.markdown()).contains("Recommendation");

        // then — verify LLM was called with correct context
        verifyCreateObject(
                prompt ->
                        prompt.contains(SERVICE_NAME)
                                && prompt.contains("Analyze the deployment impact"),
                DeployImpactReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("All data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query = agent.parseDeployQuery(new UserInput(SERVICE_NAME));

        // when
        agent.fetchDeployDetail(query);
        agent.fetchPreDeployMetrics(query);
        agent.fetchPostDeployMetrics(query);
        agent.fetchNewErrors(query);

        // then
        then(deployHistoryProvider)
                .should(atLeastOnce())
                .fetchLatestDeploy(SERVICE_NAME);
        then(deployHistoryProvider).should().fetchDeployDetail(SERVICE_NAME, DEPLOY_ID);
        then(metricsProvider)
                .should(atLeastOnce())
                .fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class));
        then(logSearchProvider)
                .should()
                .searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR"));
    }

    @Test
    @DisplayName("Formatted deploy impact report contains all expected sections")
    void shouldProduceFormattedReportWithAllSections() {
        // given
        var query = agent.parseDeployQuery(new UserInput(SERVICE_NAME));
        var report = aDeployImpactReport();

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.markdown())
                .contains("Deploy Impact: " + SERVICE_NAME)
                .contains("ROLLBACK")
                .contains("error_rate")
                .contains("NullPointerException")
                .contains("Recommendation");
    }

    @Test
    @DisplayName("Deploy detail action returns correct detail from ArgoCD")
    void shouldFetchDeployDetailFromArgoCD() {
        // given
        stubAllPorts();
        var query = agent.parseDeployQuery(new UserInput(SERVICE_NAME));

        // when
        var deployDetail = agent.fetchDeployDetail(query);

        // then
        assertThat(deployDetail.detail().deployId()).isEqualTo(DEPLOY_ID);
        assertThat(deployDetail.detail().changedFiles())
                .containsExactly("PriceEvaluationService.java", "AlertConfig.java");
        assertThat(deployDetail.detail().author()).isEqualTo("developer@example.com");
    }

    @Test
    @DisplayName("Pre-deploy metrics action returns metrics from Prometheus")
    void shouldFetchPreDeployMetricsFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseDeployQuery(new UserInput(SERVICE_NAME));

        // when
        var preMetrics = agent.fetchPreDeployMetrics(query);

        // then
        assertThat(preMetrics.snapshot().service()).isEqualTo(SERVICE_NAME);
        assertThat(preMetrics.snapshot().errorRate()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("Post-deploy metrics action returns current metrics from Prometheus")
    void shouldFetchPostDeployMetricsFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseDeployQuery(new UserInput(SERVICE_NAME));

        // when
        var postMetrics = agent.fetchPostDeployMetrics(query);

        // then
        assertThat(postMetrics.snapshot().service()).isEqualTo(SERVICE_NAME);
        assertThat(postMetrics.snapshot().latencyP99()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("New errors action returns log clusters from Loki")
    void shouldFetchNewErrorsFromLoki() {
        // given
        stubAllPorts();
        var query = agent.parseDeployQuery(new UserInput(SERVICE_NAME));

        // when
        var errors = agent.fetchNewErrors(query);

        // then
        assertThat(errors.clusters()).hasSize(1);
        assertThat(errors.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(errors.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("Graceful handling when no new errors exist after deployment")
    void shouldHandleEmptyErrors() {
        // given
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(aDeploySnapshot());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of());
        var query = agent.parseDeployQuery(new UserInput(SERVICE_NAME));

        // when
        var errors = agent.fetchNewErrors(query);

        // then
        assertThat(errors.clusters()).isEmpty();
    }
}
