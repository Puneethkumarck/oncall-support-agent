package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent.FormattedFatigueReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static com.stablebridge.oncall.fixtures.FatigueFixtures.anAlertFatigueReport;
import static com.stablebridge.oncall.fixtures.TestConstants.TEAM_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class AlertFatigueControllerTest {

    private AgentPlatform agentPlatform;
    private AlertFatigueAgent alertFatigueAgent;
    private AlertFatigueController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        alertFatigueAgent = mock(AlertFatigueAgent.class);
        controller = new AlertFatigueController(agentPlatform, alertFatigueAgent);
    }

    @Test
    @DisplayName("GET /api/v1/alert-fatigue returns formatted fatigue report")
    void shouldReturnAlertFatigueReport() {
        // given
        var expectedReport =
                new FormattedFatigueReport(
                        TEAM_NAME, 7, anAlertFatigueReport(), "# Alert Fatigue\n65% noise");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            // when
            var result = controller.analyzeAlertFatigue(TEAM_NAME, 7);

            // then
            assertThat(result.team()).isEqualTo(TEAM_NAME);
            assertThat(result.days()).isEqualTo(7);
            assertThat(result.markdown()).contains("noise");
        }
    }

    @Test
    @DisplayName("GET /api/v1/alert-fatigue uses default parameters")
    void shouldUseDefaultParameters() {
        // given
        var expectedReport =
                new FormattedFatigueReport(
                        "platform", 7, anAlertFatigueReport(), "# Alert Fatigue");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            // when — use default parameter values
            var result = controller.analyzeAlertFatigue("platform", 7);

            // then
            assertThat(result).isNotNull();
        }
    }
}
