package com.stablebridge.oncall.agent.health;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent.Dependencies;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent.ErrorSummary;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent.HealthQuery;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent.SLIMetrics;
import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.DependencyGraphProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.HealthCardFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.HealthFixtures.aDependencyStatus;
import static com.stablebridge.oncall.fixtures.HealthFixtures.aServiceHealthReport;
import static com.stablebridge.oncall.fixtures.LogFixtures.aLogCluster;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aMetricsSnapshot;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.anSLOSnapshot;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ServiceHealthAgentTest {

    @Mock private MetricsProvider metricsProvider;
    @Mock private DependencyGraphProvider dependencyGraphProvider;
    @Mock private LogSearchProvider logSearchProvider;
    @Mock private HealthCardFormatter healthCardFormatter;

    private ServiceHealthAgent agent;

    @BeforeEach
    void setUp() {
        agent =
                new ServiceHealthAgent(
                        metricsProvider,
                        dependencyGraphProvider,
                        logSearchProvider,
                        healthCardFormatter);
    }

    @Test
    @DisplayName("parseHealthQuery extracts service name from UserInput")
    void shouldParseHealthQuery() {
        // given
        var userInput = new UserInput(SERVICE_NAME);

        // when
        var result = agent.parseHealthQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("parseHealthQuery trims whitespace from service name")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseHealthQuery(userInput);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchSLIMetrics returns metrics snapshot from Prometheus")
    void shouldFetchSLIMetrics() {
        // given
        var query = new HealthQuery(SERVICE_NAME);
        var expectedMetrics = aMetricsSnapshot();
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(expectedMetrics);

        // when
        var result = agent.fetchSLIMetrics(query);

        // then
        assertThat(result.snapshot()).isEqualTo(expectedMetrics);
        assertThat(result.snapshot().service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchSLOBudget returns SLO snapshot from Prometheus")
    void shouldFetchSLOBudget() {
        // given
        var query = new HealthQuery(SERVICE_NAME);
        var expectedSLO = anSLOSnapshot();
        given(metricsProvider.fetchSLOBudget(SERVICE_NAME, "availability"))
                .willReturn(expectedSLO);

        // when
        var result = agent.fetchSLOBudget(query);

        // then
        assertThat(result).isEqualTo(expectedSLO);
        assertThat(result.burnRate()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("fetchDependencyHealth returns dependency statuses from Prometheus")
    void shouldFetchDependencyHealth() {
        // given
        var query = new HealthQuery(SERVICE_NAME);
        var deps = List.of(aDependencyStatus());
        given(dependencyGraphProvider.fetchDependencies(SERVICE_NAME)).willReturn(deps);

        // when
        var result = agent.fetchDependencyHealth(query);

        // then
        assertThat(result.statuses()).hasSize(1);
        assertThat(result.statuses().getFirst().name()).isEqualTo("evaluator");
        assertThat(result.statuses().getFirst().status()).isEqualTo(HealthStatus.GREEN);
    }

    @Test
    @DisplayName("fetchRecentErrors returns error clusters from Loki")
    void shouldFetchRecentErrors() {
        // given
        var query = new HealthQuery(SERVICE_NAME);
        var clusters = List.of(aLogCluster());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(clusters);

        // when
        var result = agent.fetchRecentErrors(query);

        // then
        assertThat(result.clusters()).hasSize(1);
        assertThat(result.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(result.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("fetchRecentErrors returns empty list when no errors")
    void shouldReturnEmptyErrorsWhenNone() {
        // given
        var query = new HealthQuery(SERVICE_NAME);
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of());

        // when
        var result = agent.fetchRecentErrors(query);

        // then
        assertThat(result.clusters()).isEmpty();
    }

    @Test
    @DisplayName("formatHealthCard delegates to HealthCardFormatter and wraps result")
    void shouldFormatHealthCard() {
        // given
        var report = aServiceHealthReport();
        var query = new HealthQuery(SERVICE_NAME);
        var expectedMarkdown = "# Service Health: alert-api [AMBER]";
        given(healthCardFormatter.format(SERVICE_NAME, report)).willReturn(expectedMarkdown);

        // when
        var result = agent.formatHealthCard(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.report().overallStatus()).isEqualTo(HealthStatus.AMBER);
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(healthCardFormatter).format(SERVICE_NAME, report);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var metrics = aMetricsSnapshot();
        var deps = List.of(aDependencyStatus());
        var clusters = List.of(aLogCluster());

        // when
        var sliMetrics = new SLIMetrics(metrics);
        var dependencies = new Dependencies(deps);
        var errorSummary = new ErrorSummary(clusters);

        // then
        assertThat(sliMetrics.snapshot()).isEqualTo(metrics);
        assertThat(dependencies.statuses()).isEqualTo(deps);
        assertThat(errorSummary.clusters()).isEqualTo(clusters);
    }
}
