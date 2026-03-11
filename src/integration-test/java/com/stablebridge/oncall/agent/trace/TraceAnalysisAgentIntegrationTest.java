package com.stablebridge.oncall.agent.trace;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.trace.TraceAnalysisReport;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.port.tempo.TraceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.LogFixtures.aLogCluster;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aMetricsSnapshot;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static com.stablebridge.oncall.fixtures.TestConstants.TRACE_ID;
import static com.stablebridge.oncall.fixtures.TraceFixtures.aBottleneckStep;
import static com.stablebridge.oncall.fixtures.TraceFixtures.aCallChainStep;
import static com.stablebridge.oncall.fixtures.TraceFixtures.aTraceAnalysisReport;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@Import(TestJacksonConfig.class)
class TraceAnalysisAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private TraceProvider traceProvider;
    private MetricsProvider metricsProvider;
    private LogSearchProvider logSearchProvider;

    private TraceAnalysisAgent agent;

    @BeforeEach
    void setUpMocks() {
        traceProvider = Mockito.mock(TraceProvider.class);
        metricsProvider = Mockito.mock(MetricsProvider.class);
        logSearchProvider = Mockito.mock(LogSearchProvider.class);

        agent =
                new TraceAnalysisAgent(
                        traceProvider,
                        metricsProvider,
                        logSearchProvider,
                        new com.stablebridge.oncall.domain.service
                                .TraceAnalysisReportFormatter());
    }

    private void stubAllPorts() {
        given(traceProvider.searchTraces(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        anyInt()))
                .willReturn(List.of(aCallChainStep(), aBottleneckStep()));
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of(aLogCluster()));
    }

    private void stubAllPortsWithTraceId() {
        given(traceProvider.fetchTrace(TRACE_ID))
                .willReturn(List.of(aCallChainStep(), aBottleneckStep()));
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of(aLogCluster()));
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput → data fetch → LLM analyze → formatted report")
    void shouldExecuteFullGoapChain() {
        // given — stub all external ports
        stubAllPorts();

        // given — stub LLM to return fixture trace analysis report
        whenCreateObject(
                        prompt -> prompt.contains(SERVICE_NAME),
                        TraceAnalysisReport.class)
                .thenReturn(aTraceAnalysisReport());

        // when — run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform,
                        TraceAnalysisAgent.FormattedTraceAnalysis.class);
        var result = invocation.invoke(new UserInput(SERVICE_NAME), agent);

        // then — verify the output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report().bottleneck().service()).isEqualTo("evaluator");
        assertThat(result.markdown()).contains("Trace Analysis");
        assertThat(result.markdown()).contains("Call Chain");
        assertThat(result.markdown()).contains("Bottleneck");
        assertThat(result.markdown()).contains("Cascade Impact");
        assertThat(result.markdown()).contains("Recommendation");

        // then — verify LLM was called with correct context
        verifyCreateObject(
                prompt ->
                        prompt.contains(SERVICE_NAME)
                                && prompt.contains("Analyze the distributed traces"),
                TraceAnalysisReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("All three data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query = agent.parseTraceQuery(new UserInput(SERVICE_NAME));

        // when
        agent.fetchDistributedTraces(query);
        agent.fetchPerServiceMetrics(query);
        agent.fetchPerServiceLogs(query);

        // then
        then(traceProvider)
                .should()
                .searchTraces(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        anyInt());
        then(metricsProvider)
                .should()
                .fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class));
        then(logSearchProvider)
                .should()
                .searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR"));
    }

    @Test
    @DisplayName("Formatted trace report contains all expected sections")
    void shouldProduceFormattedReportWithAllSections() {
        // given
        var query = agent.parseTraceQuery(new UserInput(SERVICE_NAME));
        var report = aTraceAnalysisReport();

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.markdown())
                .contains("Trace Analysis: " + SERVICE_NAME)
                .contains("alert-api")
                .contains("evaluator")
                .contains("BOTTLENECK")
                .contains("LATENCY_INCREASE")
                .contains("Increase evaluator DB connection pool");
    }

    @Test
    @DisplayName("Trace data action returns spans from Tempo via traceId")
    void shouldFetchTraceByIdFromTempo() {
        // given
        stubAllPortsWithTraceId();
        var query =
                agent.parseTraceQuery(
                        new UserInput(SERVICE_NAME + " " + TRACE_ID));

        // when
        var traceData = agent.fetchDistributedTraces(query);

        // then
        assertThat(traceData.spans()).hasSize(2);
        assertThat(traceData.spans().getFirst().service()).isEqualTo("alert-api");
        assertThat(traceData.spans().getLast().isBottleneck()).isTrue();
    }

    @Test
    @DisplayName("Trace data action searches by service when no traceId")
    void shouldSearchTracesByServiceFromTempo() {
        // given
        stubAllPorts();
        var query = agent.parseTraceQuery(new UserInput(SERVICE_NAME));

        // when
        var traceData = agent.fetchDistributedTraces(query);

        // then
        assertThat(traceData.spans()).hasSize(2);
        then(traceProvider)
                .should()
                .searchTraces(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        anyInt());
    }

    @Test
    @DisplayName("Per-service metrics action returns correct snapshot from Prometheus")
    void shouldFetchPerServiceMetricsFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseTraceQuery(new UserInput(SERVICE_NAME));

        // when
        var metricsData = agent.fetchPerServiceMetrics(query);

        // then
        assertThat(metricsData.snapshot().service()).isEqualTo(SERVICE_NAME);
        assertThat(metricsData.snapshot().errorRate()).isEqualTo(0.05);
        assertThat(metricsData.snapshot().latencyP99()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("Per-service logs action returns error clusters from Loki")
    void shouldFetchPerServiceLogsFromLoki() {
        // given
        stubAllPorts();
        var query = agent.parseTraceQuery(new UserInput(SERVICE_NAME));

        // when
        var logsData = agent.fetchPerServiceLogs(query);

        // then
        assertThat(logsData.clusters()).hasSize(1);
        assertThat(logsData.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(logsData.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("Graceful handling when no traces are found")
    void shouldHandleEmptyTraces() {
        // given
        given(traceProvider.searchTraces(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        anyInt()))
                .willReturn(List.of());
        var query = agent.parseTraceQuery(new UserInput(SERVICE_NAME));

        // when
        var traceData = agent.fetchDistributedTraces(query);

        // then
        assertThat(traceData.spans()).isEmpty();
    }

    @Test
    @DisplayName("Graceful handling when no error logs exist")
    void shouldHandleEmptyLogs() {
        // given
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of());
        var query = agent.parseTraceQuery(new UserInput(SERVICE_NAME));

        // when
        var logsData = agent.fetchPerServiceLogs(query);

        // then
        assertThat(logsData.clusters()).isEmpty();
    }
}
