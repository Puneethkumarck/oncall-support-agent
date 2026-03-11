package com.stablebridge.oncall.agent.logs;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.BaselineSnapshot;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.FormattedLogAnalysis;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.LogQuery;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.RawLogSnapshot;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.LogAnalysisReportFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeploySnapshot;
import static com.stablebridge.oncall.fixtures.LogFixtures.aLogAnalysisReport;
import static com.stablebridge.oncall.fixtures.LogFixtures.aLogCluster;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aMetricsSnapshot;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LogAnalysisAgentTest {

    @Mock private LogSearchProvider logSearchProvider;
    @Mock private DeployHistoryProvider deployHistoryProvider;
    @Mock private MetricsProvider metricsProvider;
    @Mock private LogAnalysisReportFormatter logAnalysisReportFormatter;

    private LogAnalysisAgent agent;

    @BeforeEach
    void setUp() {
        agent =
                new LogAnalysisAgent(
                        logSearchProvider,
                        deployHistoryProvider,
                        metricsProvider,
                        logAnalysisReportFormatter);
    }

    @Test
    @DisplayName("parseLogQuery extracts service name from simple input")
    void shouldParseServiceName() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseLogQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.severityFilter()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("parseLogQuery extracts service, time window, and severity from input")
    void shouldParseFullInput() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " 60 WARN");

        // when
        var result = agent.parseLogQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.severityFilter()).isEqualTo("WARN");
        assertThat(result.from()).isBefore(result.to());
    }

    @Test
    @DisplayName("parseLogQuery trims whitespace from input")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseLogQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("parseLogQuery uses default 30m window when no window specified")
    void shouldUseDefaultWindow() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseLogQuery(userInput);

        // then
        var durationMinutes =
                java.time.Duration.between(result.from(), result.to()).toMinutes();
        assertThat(durationMinutes).isEqualTo(30);
    }

    @Test
    @DisplayName("fetchErrorLogs returns log clusters from Loki")
    void shouldFetchErrorLogs() {
        // given
        var query =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        var clusters = List.of(aLogCluster());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(clusters);

        // when
        var result = agent.fetchErrorLogs(query);

        // then
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(result.totalHits()).isEqualTo(42);
    }

    @Test
    @DisplayName("fetchErrorLogs returns empty snapshot when no errors")
    void shouldReturnEmptySnapshotWhenNoErrors() {
        // given
        var query =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of());

        // when
        var result = agent.fetchErrorLogs(query);

        // then
        assertThat(result.entries()).isEmpty();
        assertThat(result.totalHits()).isZero();
    }

    @Test
    @DisplayName("fetchDeployHistory returns deploy snapshot from ArgoCD")
    void shouldFetchDeployHistory() {
        // given
        var query =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        var expectedDeploy = aDeploySnapshot();
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(expectedDeploy);

        // when
        var result = agent.fetchDeployHistory(query);

        // then
        assertThat(result.appName()).isEqualTo(SERVICE_NAME);
        assertThat(result.syncStatus()).isEqualTo("Synced");
        verify(deployHistoryProvider).fetchLatestDeploy(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchBaselineMetrics returns baseline and current error rates from Prometheus")
    void shouldFetchBaselineMetrics() {
        // given
        var query =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        var metrics = aMetricsSnapshot();
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(metrics);

        // when
        var result = agent.fetchBaselineMetrics(query);

        // then
        assertThat(result.normalErrorRate()).isEqualTo(0.05);
        assertThat(result.currentErrorRate()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("formatReport delegates to LogAnalysisReportFormatter and wraps result")
    void shouldFormatReport() {
        // given
        var report = aLogAnalysisReport();
        var query =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        var expectedMarkdown = "# Log Analysis: alert-api";
        given(logAnalysisReportFormatter.format(SERVICE_NAME, report))
                .willReturn(expectedMarkdown);

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.query()).isEqualTo(query);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.report().clusters()).hasSize(1);
        assertThat(result.report().newPatterns()).hasSize(1);
        assertThat(result.formattedBody()).isEqualTo(expectedMarkdown);
        verify(logAnalysisReportFormatter).format(SERVICE_NAME, report);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var clusters = List.of(aLogCluster());

        // when
        var logQuery =
                new LogQuery(
                        SERVICE_NAME,
                        Instant.parse("2026-03-10T09:30:00Z"),
                        Instant.parse("2026-03-10T10:00:00Z"),
                        "ERROR");
        var rawLogSnapshot = new RawLogSnapshot(clusters, 42);
        var baselineSnapshot = new BaselineSnapshot(0.01, 0.05);
        var formattedLogAnalysis =
                new FormattedLogAnalysis(logQuery, aLogAnalysisReport(), "markdown");

        // then
        assertThat(logQuery.service()).isEqualTo(SERVICE_NAME);
        assertThat(logQuery.severityFilter()).isEqualTo("ERROR");
        assertThat(rawLogSnapshot.entries()).isEqualTo(clusters);
        assertThat(rawLogSnapshot.totalHits()).isEqualTo(42);
        assertThat(baselineSnapshot.normalErrorRate()).isEqualTo(0.01);
        assertThat(baselineSnapshot.currentErrorRate()).isEqualTo(0.05);
        assertThat(formattedLogAnalysis.query()).isEqualTo(logQuery);
        assertThat(formattedLogAnalysis.formattedBody()).isEqualTo("markdown");
    }
}
