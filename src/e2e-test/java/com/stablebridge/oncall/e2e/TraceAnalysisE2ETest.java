package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.FormattedTraceAnalysis;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.trace.TraceAnalysisReport;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.port.tempo.TraceProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static com.stablebridge.oncall.fixtures.TraceFixtures.aTraceAnalysisReport;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class TraceAnalysisE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String TARGET_SERVICE = "alert-api";

    @Autowired
    private TraceAnalysisAgent traceAnalysisAgent;

    @Autowired
    private TraceProvider traceProvider;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private LogSearchProvider logSearchProvider;

    @Test
    @DisplayName("E2E: Trace analysis on alert-api produces report from live Tempo + Prometheus + Loki data")
    void shouldProduceTraceAnalysisFromLiveData() {
        // given — stub LLM to return a trace analysis report
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_SERVICE), TraceAnalysisReport.class)
                .thenReturn(aTraceAnalysisReport());

        // when — run agent with real Tempo + Prometheus + Loki adapters (search mode, no traceId)
        var invocation = AgentInvocation.create(agentPlatform, FormattedTraceAnalysis.class);
        var result = invocation.invoke(new UserInput(TARGET_SERVICE), traceAnalysisAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(TARGET_SERVICE);
        assertThat(result.report()).isNotNull();
        assertThat(result.report().callChain()).isNotEmpty();
        assertThat(result.markdown()).isNotBlank();

        // then — verify LLM was called (meaning real data was fetched successfully)
        verifyCreateObject(
                prompt -> prompt.contains(TARGET_SERVICE) && prompt.contains("Analyze"),
                TraceAnalysisReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("E2E: Tempo adapter returns real trace data for alert-api")
    void shouldFetchRealTracesFromTempo() {
        // when — search for traces via Tempo directly
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(30));
        var traces = traceProvider.searchTraces(TARGET_SERVICE, from, to, 5);

        // then — verify we got a response (may be empty if no traces)
        assertThat(traces).isNotNull();
    }

    @Test
    @DisplayName("E2E: Prometheus and Loki return real data for trace correlation")
    void shouldFetchCorrelatedDataFromPrometheusAndLoki() {
        // when + then — Prometheus returns metrics
        var metrics = metricsProvider.fetchServiceMetrics(TARGET_SERVICE, Instant.now());
        assertThat(metrics).isNotNull();
        assertThat(metrics.service()).isEqualTo(TARGET_SERVICE);

        // when + then — Loki returns logs (may be empty)
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(30));
        var logs = logSearchProvider.searchLogs(TARGET_SERVICE, from, to, "ERROR");
        assertThat(logs).isNotNull();
    }
}
