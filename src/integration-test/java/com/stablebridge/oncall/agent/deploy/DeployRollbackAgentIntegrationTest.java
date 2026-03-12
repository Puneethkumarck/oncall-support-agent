package com.stablebridge.oncall.agent.deploy;

import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.argocd.DeployRollbackProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

import java.time.Instant;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeploySnapshot;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aRollbackHistory;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aRollbackResult;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aMetricsSnapshot;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static com.stablebridge.oncall.fixtures.TestConstants.TARGET_REVISION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@Import(TestJacksonConfig.class)
class DeployRollbackAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private DeployHistoryProvider deployHistoryProvider;
    private DeployRollbackProvider deployRollbackProvider;
    private MetricsProvider metricsProvider;

    private DeployRollbackAgent agent;

    @BeforeEach
    void setUpMocks() {
        deployHistoryProvider = Mockito.mock(DeployHistoryProvider.class);
        deployRollbackProvider = Mockito.mock(DeployRollbackProvider.class);
        metricsProvider = Mockito.mock(MetricsProvider.class);

        agent =
                new DeployRollbackAgent(
                        deployHistoryProvider,
                        deployRollbackProvider,
                        metricsProvider,
                        new com.stablebridge.oncall.domain.service
                                .RollbackReportFormatter());
    }

    private void stubAllPorts() {
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(aDeploySnapshot());
        given(deployHistoryProvider.fetchRollbackHistory(SERVICE_NAME))
                .willReturn(aRollbackHistory());
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
        given(deployRollbackProvider.executeRollback(eq(SERVICE_NAME), any(String.class)))
                .willReturn(aRollbackResult());
    }

    @Test
    @DisplayName("All data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query = agent.parseRollbackRequest(new UserInput(SERVICE_NAME));

        // when
        agent.fetchRollbackContext(query);

        // then
        then(deployHistoryProvider).should().fetchLatestDeploy(SERVICE_NAME);
        then(deployHistoryProvider).should().fetchRollbackHistory(SERVICE_NAME);
    }

    @Test
    @DisplayName("Rollback context contains deploy snapshot and rollback history")
    void shouldFetchRollbackContextFromArgoCD() {
        // given
        stubAllPorts();
        var query = agent.parseRollbackRequest(new UserInput(SERVICE_NAME));

        // when
        var context = agent.fetchRollbackContext(query);

        // then
        assertThat(context.currentDeploy().appName()).isEqualTo(SERVICE_NAME);
        assertThat(context.history().canRollback()).isTrue();
        assertThat(context.history().lastStableRevision()).isEqualTo(TARGET_REVISION);
    }

    @Test
    @DisplayName("Formatted rollback report contains all expected sections")
    void shouldProduceFormattedReportWithAllSections() {
        // given
        var query = agent.parseRollbackRequest(new UserInput(SERVICE_NAME));
        var rollbackResult = aRollbackResult();
        var execution =
                new DeployRollbackAgent.RollbackExecutionResult(rollbackResult);

        // when
        var result = agent.formatReport(execution, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.result()).isEqualTo(rollbackResult);
        assertThat(result.markdown())
                .contains("Rollback Report: " + SERVICE_NAME)
                .contains("SUCCESS")
                .contains(TARGET_REVISION)
                .contains("Execution")
                .contains("Details");
    }

    @Test
    @DisplayName("Execute rollback action calls DeployRollbackProvider")
    void shouldExecuteRollbackViaProvider() {
        // given
        stubAllPorts();
        var plan =
                new DeployRollbackAgent.RollbackPlan(
                        SERVICE_NAME,
                        TARGET_REVISION,
                        "deploy-abc123",
                        "Low risk",
                        "Brief downtime expected");
        var approved = new DeployRollbackAgent.ApprovedRollbackPlan(plan);

        // when
        var result = agent.executeRollback(approved);

        // then
        assertThat(result.result().success()).isTrue();
        assertThat(result.result().service()).isEqualTo(SERVICE_NAME);
        then(deployRollbackProvider).should().executeRollback(SERVICE_NAME, TARGET_REVISION);
    }

    @Test
    @DisplayName("Graceful handling when no rollback history exists")
    void shouldHandleNoRollbackHistory() {
        // given
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(aDeploySnapshot());
        given(deployHistoryProvider.fetchRollbackHistory(SERVICE_NAME))
                .willReturn(new com.stablebridge.oncall.domain.model.deploy.RollbackHistory(
                        java.util.List.of(), false, "deploy-abc123", ""));
        var query = agent.parseRollbackRequest(new UserInput(SERVICE_NAME));

        // when
        var context = agent.fetchRollbackContext(query);

        // then
        assertThat(context.history().canRollback()).isFalse();
        assertThat(context.history().previousRevisions()).isEmpty();
    }
}
