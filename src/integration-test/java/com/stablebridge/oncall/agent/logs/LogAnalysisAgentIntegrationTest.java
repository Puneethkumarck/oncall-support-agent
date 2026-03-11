package com.stablebridge.oncall.agent.logs;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.logs.LogAnalysisReport;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

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
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

@Import(TestJacksonConfig.class)
class LogAnalysisAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private LogSearchProvider logSearchProvider;
    private DeployHistoryProvider deployHistoryProvider;
    private MetricsProvider metricsProvider;

    private LogAnalysisAgent agent;

    @BeforeEach
    void setUpMocks() {
        logSearchProvider = Mockito.mock(LogSearchProvider.class);
        deployHistoryProvider = Mockito.mock(DeployHistoryProvider.class);
        metricsProvider = Mockito.mock(MetricsProvider.class);

        agent =
                new LogAnalysisAgent(
                        logSearchProvider,
                        deployHistoryProvider,
                        metricsProvider,
                        new com.stablebridge.oncall.domain.service.LogAnalysisReportFormatter());
    }

    private void stubAllPorts() {
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of(aLogCluster()));
        given(deployHistoryProvider.fetchLatestDeploy(SERVICE_NAME))
                .willReturn(aDeploySnapshot());
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput → data fetch → LLM analyze → formatted report")
    void shouldExecuteFullGoapChain() {
        // given — stub all external ports
        stubAllPorts();

        // given — stub LLM to return fixture log analysis report
        whenCreateObject(
                        prompt -> prompt.contains(SERVICE_NAME), LogAnalysisReport.class)
                .thenReturn(aLogAnalysisReport());

        // when — run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform, LogAnalysisAgent.FormattedLogAnalysis.class);
        var result = invocation.invoke(new UserInput(SERVICE_NAME), agent);

        // then — verify the output
        assertThat(result).isNotNull();
        assertThat(result.query().service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report().clusters()).hasSize(1);
        assertThat(result.report().newPatterns()).hasSize(1);
        assertThat(result.report().deployCorrelation().isCorrelated()).isTrue();
        assertThat(result.formattedBody()).contains("Log Analysis");
        assertThat(result.formattedBody()).contains(SERVICE_NAME);
        assertThat(result.formattedBody()).contains("NullPointerException");
        assertThat(result.formattedBody()).contains("Recommendation");

        // then — verify LLM was called with correct context
        verifyCreateObject(
                prompt -> prompt.contains(SERVICE_NAME) && prompt.contains("Analyze"),
                LogAnalysisReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("All three data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query = agent.parseLogQuery(new UserInput(SERVICE_NAME));

        // when
        agent.fetchErrorLogs(query);
        agent.fetchDeployHistory(query);
        agent.fetchBaselineMetrics(query);

        // then
        then(logSearchProvider)
                .should()
                .searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR"));
        then(deployHistoryProvider).should().fetchLatestDeploy(SERVICE_NAME);
        then(metricsProvider)
                .should(times(2))
                .fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class));
    }

    @Test
    @DisplayName("Formatted log analysis report contains all expected sections")
    void shouldProduceFormattedReportWithAllSections() {
        // given
        var query = agent.parseLogQuery(new UserInput(SERVICE_NAME));
        var report = aLogAnalysisReport();

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.query().service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.formattedBody())
                .contains("Log Analysis: " + SERVICE_NAME)
                .contains("Error Clusters")
                .contains("NullPointerException")
                .contains("[NEW]")
                .contains("New Patterns")
                .contains("Deploy Correlation")
                .contains("Recommendation");
    }

    @Test
    @DisplayName("Error logs action returns clusters from Loki")
    void shouldFetchErrorLogsFromLoki() {
        // given
        stubAllPorts();
        var query = agent.parseLogQuery(new UserInput(SERVICE_NAME));

        // when
        var rawLogs = agent.fetchErrorLogs(query);

        // then
        assertThat(rawLogs.entries()).hasSize(1);
        assertThat(rawLogs.entries().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(rawLogs.entries().getFirst().isNew()).isTrue();
        assertThat(rawLogs.totalHits()).isEqualTo(42);
    }

    @Test
    @DisplayName("Deploy history action returns snapshot from ArgoCD")
    void shouldFetchDeployHistoryFromArgoCD() {
        // given
        stubAllPorts();
        var query = agent.parseLogQuery(new UserInput(SERVICE_NAME));

        // when
        var deploy = agent.fetchDeployHistory(query);

        // then
        assertThat(deploy.appName()).isEqualTo(SERVICE_NAME);
        assertThat(deploy.syncStatus()).isEqualTo("Synced");
        assertThat(deploy.health()).isEqualTo("Healthy");
    }

    @Test
    @DisplayName("Baseline metrics action returns error rate comparison from Prometheus")
    void shouldFetchBaselineMetricsFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseLogQuery(new UserInput(SERVICE_NAME));

        // when
        var baseline = agent.fetchBaselineMetrics(query);

        // then
        assertThat(baseline.normalErrorRate()).isEqualTo(0.05);
        assertThat(baseline.currentErrorRate()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("Graceful handling when no error logs exist")
    void shouldHandleEmptyErrorLogs() {
        // given
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of());
        var query = agent.parseLogQuery(new UserInput(SERVICE_NAME));

        // when
        var rawLogs = agent.fetchErrorLogs(query);

        // then
        assertThat(rawLogs.entries()).isEmpty();
        assertThat(rawLogs.totalHits()).isZero();
    }

    @Test
    @DisplayName("Log query parses custom time window and severity")
    void shouldParseCustomTimeWindowAndSeverity() {
        // given
        var userInput = new UserInput(SERVICE_NAME + " 60 WARN");

        // when
        var query = agent.parseLogQuery(userInput);

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
        assertThat(query.severityFilter()).isEqualTo("WARN");
        var durationMinutes =
                java.time.Duration.between(query.from(), query.to()).toMinutes();
        assertThat(durationMinutes).isEqualTo(60);
    }
}
