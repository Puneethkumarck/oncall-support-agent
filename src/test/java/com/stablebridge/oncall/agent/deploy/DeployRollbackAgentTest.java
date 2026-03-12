package com.stablebridge.oncall.agent.deploy;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.deploy.DeployRollbackAgent.ApprovedRollbackPlan;
import com.stablebridge.oncall.agent.deploy.DeployRollbackAgent.RollbackContextData;
import com.stablebridge.oncall.agent.deploy.DeployRollbackAgent.RollbackExecutionResult;
import com.stablebridge.oncall.agent.deploy.DeployRollbackAgent.RollbackPlan;
import com.stablebridge.oncall.agent.deploy.DeployRollbackAgent.RollbackQuery;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.argocd.DeployRollbackProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.RollbackReportFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeploySnapshot;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aRollbackHistory;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aRollbackResult;
import static com.stablebridge.oncall.fixtures.TestConstants.DEPLOY_ID;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static com.stablebridge.oncall.fixtures.TestConstants.TARGET_REVISION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DeployRollbackAgentTest {

    @Mock private DeployHistoryProvider deployHistoryProvider;
    @Mock private DeployRollbackProvider deployRollbackProvider;
    @Mock private MetricsProvider metricsProvider;
    @Mock private RollbackReportFormatter rollbackReportFormatter;

    private DeployRollbackAgent agent;

    @BeforeEach
    void setUp() {
        agent =
                new DeployRollbackAgent(
                        deployHistoryProvider,
                        deployRollbackProvider,
                        metricsProvider,
                        rollbackReportFormatter);
    }

    @Test
    @DisplayName("parseRollbackRequest extracts service name from UserInput")
    void shouldParseRollbackRequest() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseRollbackRequest(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("parseRollbackRequest trims whitespace from input")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseRollbackRequest(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchRollbackContext returns deploy and rollback history from ArgoCD")
    void shouldFetchRollbackContext() {
        // given
        var query = new RollbackQuery(SERVICE_NAME);
        var snapshot = aDeploySnapshot();
        var history = aRollbackHistory();
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME)).willReturn(snapshot);
        given(deployHistoryProvider.fetchRollbackHistory(SERVICE_NAME)).willReturn(history);

        // when
        var result = agent.fetchRollbackContext(query);

        // then
        assertThat(result.currentDeploy()).isEqualTo(snapshot);
        assertThat(result.history()).isEqualTo(history);
        assertThat(result.history().canRollback()).isTrue();
        assertThat(result.history().lastStableRevision()).isEqualTo(TARGET_REVISION);
    }

    @Test
    @DisplayName("executeRollback calls DeployRollbackProvider and wraps result")
    void shouldExecuteRollback() {
        // given
        var plan =
                new RollbackPlan(
                        SERVICE_NAME,
                        TARGET_REVISION,
                        DEPLOY_ID,
                        "Low risk",
                        "Brief downtime expected");
        var approved = new ApprovedRollbackPlan(plan);
        var rollbackResult = aRollbackResult();
        given(deployRollbackProvider.executeRollback(SERVICE_NAME, TARGET_REVISION))
                .willReturn(rollbackResult);

        // when
        var result = agent.executeRollback(approved);

        // then
        assertThat(result.result()).isEqualTo(rollbackResult);
        assertThat(result.result().success()).isTrue();
        verify(deployRollbackProvider).executeRollback(SERVICE_NAME, TARGET_REVISION);
    }

    @Test
    @DisplayName("formatReport delegates to RollbackReportFormatter and wraps result")
    void shouldFormatReport() {
        // given
        var rollbackResult = aRollbackResult();
        var execution = new RollbackExecutionResult(rollbackResult);
        var query = new RollbackQuery(SERVICE_NAME);
        var expectedMarkdown = "# Rollback Report: alert-api [SUCCESS]";
        given(rollbackReportFormatter.format(rollbackResult)).willReturn(expectedMarkdown);

        // when
        var result = agent.formatReport(execution, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.result()).isEqualTo(rollbackResult);
        assertThat(result.result().success()).isTrue();
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(rollbackReportFormatter).format(rollbackResult);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var snapshot = aDeploySnapshot();
        var history = aRollbackHistory();
        var rollbackResult = aRollbackResult();

        // when
        var query = new RollbackQuery(SERVICE_NAME);
        var context = new RollbackContextData(history, snapshot);
        var plan =
                new RollbackPlan(
                        SERVICE_NAME,
                        TARGET_REVISION,
                        DEPLOY_ID,
                        "Low risk",
                        "Minimal impact");
        var approved = new ApprovedRollbackPlan(plan);
        var execution = new RollbackExecutionResult(rollbackResult);

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
        assertThat(context.history()).isEqualTo(history);
        assertThat(context.currentDeploy()).isEqualTo(snapshot);
        assertThat(approved.plan()).isEqualTo(plan);
        assertThat(execution.result()).isEqualTo(rollbackResult);
    }
}
