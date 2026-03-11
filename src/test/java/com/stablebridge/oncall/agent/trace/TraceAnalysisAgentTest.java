package com.stablebridge.oncall.agent.trace;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.ServiceLogsData;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.ServiceMetricsData;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.TraceData;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.TraceQuery;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.port.tempo.TraceProvider;
import com.stablebridge.oncall.domain.service.TraceAnalysisReportFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TraceAnalysisAgentTest {

    @Mock private TraceProvider traceProvider;
    @Mock private MetricsProvider metricsProvider;
    @Mock private LogSearchProvider logSearchProvider;
    @Mock private TraceAnalysisReportFormatter traceAnalysisReportFormatter;

    private TraceAnalysisAgent agent;

    @BeforeEach
    void setUp() {
        agent =
                new TraceAnalysisAgent(
                        traceProvider,
                        metricsProvider,
                        logSearchProvider,
                        traceAnalysisReportFormatter);
    }

    @Test
    @DisplayName("parseTraceQuery extracts service name from single-word input")
    void shouldParseServiceOnly() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseTraceQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.traceId()).isNull();
        assertThat(result.from()).isBefore(result.to());
    }

    @Test
    @DisplayName("parseTraceQuery extracts service name and trace ID")
    void shouldParseServiceAndTraceId() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " " + TRACE_ID);

        // when
        var result = agent.parseTraceQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("parseTraceQuery extracts service, trace ID, and custom time window")
    void shouldParseServiceTraceIdAndWindow() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " " + TRACE_ID + " 60");

        // when
        var result = agent.parseTraceQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("parseTraceQuery trims whitespace from input")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseTraceQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchDistributedTraces uses traceId when provided")
    void shouldFetchTraceById() {
        // given
        var query = new TraceQuery(SERVICE_NAME, TRACE_ID, Instant.now(), Instant.now());
        var spans = List.of(aCallChainStep(), aBottleneckStep());
        given(traceProvider.fetchTrace(TRACE_ID)).willReturn(spans);

        // when
        var result = agent.fetchDistributedTraces(query);

        // then
        assertThat(result.spans()).hasSize(2);
        assertThat(result.spans().getFirst().service()).isEqualTo("alert-api");
        verify(traceProvider).fetchTrace(TRACE_ID);
    }

    @Test
    @DisplayName("fetchDistributedTraces searches by service when no traceId")
    void shouldSearchTracesWhenNoTraceId() {
        // given
        var query = new TraceQuery(SERVICE_NAME, null, Instant.now(), Instant.now());
        var spans = List.of(aCallChainStep());
        given(traceProvider.searchTraces(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), anyInt()))
                .willReturn(spans);

        // when
        var result = agent.fetchDistributedTraces(query);

        // then
        assertThat(result.spans()).hasSize(1);
        verify(traceProvider)
                .searchTraces(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        anyInt());
    }

    @Test
    @DisplayName("fetchPerServiceMetrics returns metrics snapshot from Prometheus")
    void shouldFetchPerServiceMetrics() {
        // given
        var query = new TraceQuery(SERVICE_NAME, null, Instant.now(), Instant.now());
        var expectedMetrics = aMetricsSnapshot();
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(expectedMetrics);

        // when
        var result = agent.fetchPerServiceMetrics(query);

        // then
        assertThat(result.snapshot()).isEqualTo(expectedMetrics);
        assertThat(result.snapshot().service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchPerServiceLogs returns error clusters from Loki")
    void shouldFetchPerServiceLogs() {
        // given
        var query = new TraceQuery(SERVICE_NAME, null, Instant.now(), Instant.now());
        var clusters = List.of(aLogCluster());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(clusters);

        // when
        var result = agent.fetchPerServiceLogs(query);

        // then
        assertThat(result.clusters()).hasSize(1);
        assertThat(result.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
    }

    @Test
    @DisplayName("fetchPerServiceLogs returns empty list when no errors")
    void shouldReturnEmptyLogsWhenNone() {
        // given
        var query = new TraceQuery(SERVICE_NAME, null, Instant.now(), Instant.now());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of());

        // when
        var result = agent.fetchPerServiceLogs(query);

        // then
        assertThat(result.clusters()).isEmpty();
    }

    @Test
    @DisplayName("formatReport delegates to TraceAnalysisReportFormatter and wraps result")
    void shouldFormatReport() {
        // given
        var report = aTraceAnalysisReport();
        var query = new TraceQuery(SERVICE_NAME, TRACE_ID, Instant.now(), Instant.now());
        var expectedMarkdown = "# Trace Analysis: alert-api";
        given(traceAnalysisReportFormatter.format(SERVICE_NAME, report))
                .willReturn(expectedMarkdown);

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.report().bottleneck().service()).isEqualTo("evaluator");
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(traceAnalysisReportFormatter).format(SERVICE_NAME, report);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var spans = List.of(aCallChainStep(), aBottleneckStep());
        var metrics = aMetricsSnapshot();
        var clusters = List.of(aLogCluster());

        // when
        var traceData = new TraceData(spans);
        var metricsData = new ServiceMetricsData(metrics);
        var logsData = new ServiceLogsData(clusters);

        // then
        assertThat(traceData.spans()).hasSize(2);
        assertThat(metricsData.snapshot()).isEqualTo(metrics);
        assertThat(logsData.clusters()).isEqualTo(clusters);
    }
}
