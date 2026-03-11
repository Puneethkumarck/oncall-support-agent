package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent.FormattedHealthCard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static com.stablebridge.oncall.fixtures.HealthFixtures.aServiceHealthReport;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class HealthControllerTest {

    private AgentPlatform agentPlatform;
    private ServiceHealthAgent serviceHealthAgent;
    private HealthController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        serviceHealthAgent = mock(ServiceHealthAgent.class);
        controller = new HealthController(agentPlatform, serviceHealthAgent);
    }

    @Test
    @DisplayName("GET /api/v1/health/{service} returns formatted health card")
    void shouldReturnHealthCard() {
        // given
        var expectedReport = aServiceHealthReport();
        var expectedCard =
                new FormattedHealthCard(SERVICE_NAME, expectedReport, "# Health Card\nAMBER");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedCard);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            // when
            var result = controller.checkHealth(SERVICE_NAME);

            // then
            assertThat(result.service()).isEqualTo(SERVICE_NAME);
            assertThat(result.markdown()).contains("AMBER");
        }
    }

    @Test
    @DisplayName("GET /api/v1/health/{service} passes service name as UserInput")
    void shouldPassServiceNameAsUserInput() {
        // given
        var expectedReport = aServiceHealthReport();
        var expectedCard =
                new FormattedHealthCard(SERVICE_NAME, expectedReport, "# Health Card");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedCard);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            // when
            var result = controller.checkHealth("price-tracker");

            // then
            assertThat(result).isNotNull();
        }
    }
}
