package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.FormattedLogAnalysis;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.LogQuery;
import com.stablebridge.oncall.application.controller.LogAnalysisController.LogAnalysisRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.time.Instant;

import static com.stablebridge.oncall.fixtures.LogFixtures.aLogAnalysisReport;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class LogAnalysisControllerTest {

    private AgentPlatform agentPlatform;
    private LogAnalysisAgent logAnalysisAgent;
    private LogAnalysisController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        logAnalysisAgent = mock(LogAnalysisAgent.class);
        controller = new LogAnalysisController(agentPlatform, logAnalysisAgent);
    }

    @Test
    @DisplayName("POST /api/v1/log-analysis returns formatted log analysis report")
    void shouldReturnLogAnalysisReport() {
        // given
        var logQuery =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        var expectedReport =
                new FormattedLogAnalysis(
                        logQuery, aLogAnalysisReport(), "# Log Analysis\n2 clusters");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new LogAnalysisRequest(SERVICE_NAME, "30", "ERROR");

            // when
            var result = controller.analyzeLogPatterns(request);

            // then
            assertThat(result.query().service()).isEqualTo(SERVICE_NAME);
            assertThat(result.formattedBody()).contains("Log Analysis");
        }
    }

    @Test
    @DisplayName("POST /api/v1/log-analysis works with minimal parameters")
    void shouldWorkWithMinimalParameters() {
        // given
        var logQuery =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        var expectedReport =
                new FormattedLogAnalysis(logQuery, aLogAnalysisReport(), "# Log Analysis");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new LogAnalysisRequest(SERVICE_NAME, null, null);

            // when
            var result = controller.analyzeLogPatterns(request);

            // then
            assertThat(result).isNotNull();
        }
    }
}
