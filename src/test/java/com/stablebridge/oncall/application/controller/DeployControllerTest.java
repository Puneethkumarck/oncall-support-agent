package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.FormattedDeployImpact;
import com.stablebridge.oncall.agent.deploy.DeployRollbackAgent;
import com.stablebridge.oncall.agent.deploy.DeployRollbackAgent.FormattedRollbackReport;
import com.stablebridge.oncall.application.controller.DeployController.DeployImpactRequest;
import com.stablebridge.oncall.application.controller.DeployController.RollbackRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeployImpactReport;
import static com.stablebridge.oncall.fixtures.DeployFixtures.aRollbackResult;
import static com.stablebridge.oncall.fixtures.TestConstants.DEPLOY_ID;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class DeployControllerTest {

    private AgentPlatform agentPlatform;
    private DeployImpactAgent deployImpactAgent;
    private DeployRollbackAgent deployRollbackAgent;
    private DeployController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        deployImpactAgent = mock(DeployImpactAgent.class);
        deployRollbackAgent = mock(DeployRollbackAgent.class);
        controller =
                new DeployController(
                        agentPlatform, deployImpactAgent, deployRollbackAgent);
    }

    @Test
    @DisplayName("POST /api/v1/deploy-impact returns formatted deploy impact report")
    void shouldReturnDeployImpactReport() {
        // given
        var expectedReport =
                new FormattedDeployImpact(
                        SERVICE_NAME, aDeployImpactReport(), "# Deploy Impact\nROLLBACK");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new DeployImpactRequest(SERVICE_NAME, DEPLOY_ID);

            // when
            var result = controller.analyzeDeployImpact(request);

            // then
            assertThat(result.service()).isEqualTo(SERVICE_NAME);
            assertThat(result.markdown()).contains("ROLLBACK");
        }
    }

    @Test
    @DisplayName("POST /api/v1/deploy-impact works without deployId")
    void shouldWorkWithoutDeployId() {
        // given
        var expectedReport =
                new FormattedDeployImpact(
                        SERVICE_NAME, aDeployImpactReport(), "# Deploy Impact");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new DeployImpactRequest(SERVICE_NAME, null);

            // when
            var result = controller.analyzeDeployImpact(request);

            // then
            assertThat(result).isNotNull();
        }
    }

    @Test
    @DisplayName("POST /api/v1/rollback returns formatted rollback report")
    void shouldReturnRollbackReport() {
        // given
        var expectedReport =
                new FormattedRollbackReport(
                        SERVICE_NAME, aRollbackResult(), "# Rollback Report\nSUCCESS");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new RollbackRequest(SERVICE_NAME);

            // when
            var result = controller.executeRollback(request);

            // then
            assertThat(result.service()).isEqualTo(SERVICE_NAME);
            assertThat(result.result().success()).isTrue();
            assertThat(result.markdown()).contains("SUCCESS");
        }
    }
}
