package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.FormattedTraceAnalysis;
import com.stablebridge.oncall.application.controller.TraceController.TraceAnalysisRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static com.stablebridge.oncall.fixtures.TestConstants.TRACE_ID;
import static com.stablebridge.oncall.fixtures.TraceFixtures.aTraceAnalysisReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class TraceControllerTest {

    private AgentPlatform agentPlatform;
    private TraceAnalysisAgent traceAnalysisAgent;
    private TraceController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        traceAnalysisAgent = mock(TraceAnalysisAgent.class);
        controller = new TraceController(agentPlatform, traceAnalysisAgent);
    }

    @Test
    @DisplayName("POST /api/v1/trace-analysis returns formatted trace analysis report")
    void shouldReturnTraceAnalysisReport() {
        // given
        var expectedReport =
                new FormattedTraceAnalysis(
                        SERVICE_NAME,
                        aTraceAnalysisReport(),
                        "# Trace Analysis\nBottleneck: evaluator");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new TraceAnalysisRequest(SERVICE_NAME, TRACE_ID);

            // when
            var result = controller.analyzeTraces(request);

            // then
            assertThat(result.service()).isEqualTo(SERVICE_NAME);
            assertThat(result.markdown()).contains("evaluator");
        }
    }

    @Test
    @DisplayName("POST /api/v1/trace-analysis works without traceId")
    void shouldWorkWithoutTraceId() {
        // given
        var expectedReport =
                new FormattedTraceAnalysis(
                        SERVICE_NAME, aTraceAnalysisReport(), "# Trace Analysis");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new TraceAnalysisRequest(SERVICE_NAME, null);

            // when
            var result = controller.analyzeTraces(request);

            // then
            assertThat(result).isNotNull();
        }
    }
}
