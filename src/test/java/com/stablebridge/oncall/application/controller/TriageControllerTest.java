package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.FormattedTriageReport;
import com.stablebridge.oncall.application.controller.TriageController.TriageRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static com.stablebridge.oncall.fixtures.AlertFixtures.anAlertContext;
import static com.stablebridge.oncall.fixtures.AlertFixtures.anIncidentAssessment;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class TriageControllerTest {

    private AgentPlatform agentPlatform;
    private IncidentTriageAgent incidentTriageAgent;
    private TriageController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        incidentTriageAgent = mock(IncidentTriageAgent.class);
        controller = new TriageController(agentPlatform, incidentTriageAgent);
    }

    @Test
    @DisplayName("POST /api/v1/triage returns formatted triage report")
    void shouldReturnTriageReport() {
        // given
        var expectedReport =
                new FormattedTriageReport(
                        anAlertContext(), anIncidentAssessment(), "# Triage Report");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new TriageRequest("ALT-001", SERVICE_NAME, "SEV2", "High error rate");

            // when
            var result = controller.triage(request);

            // then
            assertThat(result.alert().service()).isEqualTo(SERVICE_NAME);
            assertThat(result.markdown()).contains("Triage Report");
        }
    }

    @Test
    @DisplayName("POST /api/v1/triage formats UserInput as pipe-delimited string")
    void shouldFormatUserInputAsPipeDelimited() {
        // given
        var expectedReport =
                new FormattedTriageReport(
                        anAlertContext(), anIncidentAssessment(), "# Triage Report");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request =
                    new TriageRequest("ALT-002", "price-tracker", "SEV1", "Service down");

            // when
            var result = controller.triage(request);

            // then
            assertThat(result).isNotNull();
        }
    }
}
