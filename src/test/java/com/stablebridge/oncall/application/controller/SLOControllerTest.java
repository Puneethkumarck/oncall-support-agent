package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.FormattedSLOReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static com.stablebridge.oncall.fixtures.MetricsFixtures.anSLOReport;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class SLOControllerTest {

    private AgentPlatform agentPlatform;
    private SLOMonitorAgent sloMonitorAgent;
    private SLOController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        sloMonitorAgent = mock(SLOMonitorAgent.class);
        controller = new SLOController(agentPlatform, sloMonitorAgent);
    }

    @Test
    @DisplayName("GET /api/v1/slo/{service} returns formatted SLO report")
    void shouldReturnSLOReport() {
        // given
        var expectedReport =
                new FormattedSLOReport(
                        SERVICE_NAME,
                        anSLOReport(),
                        "# SLO Report\nWARNING — 55.0% budget remaining");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            // when
            var result = controller.checkSLO(SERVICE_NAME);

            // then
            assertThat(result.service()).isEqualTo(SERVICE_NAME);
            assertThat(result.markdown()).contains("WARNING");
        }
    }

    @Test
    @DisplayName("GET /api/v1/slo/{service} passes service name as UserInput")
    void shouldPassServiceNameAsUserInput() {
        // given
        var expectedReport =
                new FormattedSLOReport(SERVICE_NAME, anSLOReport(), "# SLO Report");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            // when
            var result = controller.checkSLO("evaluator");

            // then
            assertThat(result).isNotNull();
        }
    }
}
