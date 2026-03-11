package com.stablebridge.oncall.agent.health;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.health.ServiceHealthReport;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.DependencyGraphProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

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
import static org.mockito.BDDMockito.then;

@Import(TestJacksonConfig.class)
class ServiceHealthAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private MetricsProvider metricsProvider;
    private DependencyGraphProvider dependencyGraphProvider;
    private LogSearchProvider logSearchProvider;

    private ServiceHealthAgent agent;

    @BeforeEach
    void setUpMocks() {
        metricsProvider = Mockito.mock(MetricsProvider.class);
        dependencyGraphProvider = Mockito.mock(DependencyGraphProvider.class);
        logSearchProvider = Mockito.mock(LogSearchProvider.class);

        agent =
                new ServiceHealthAgent(
                        metricsProvider,
                        dependencyGraphProvider,
                        logSearchProvider,
                        new com.stablebridge.oncall.domain.service.HealthCardFormatter());
    }

    private void stubAllPorts() {
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
        given(metricsProvider.fetchSLOBudget(SERVICE_NAME, "availability"))
                .willReturn(anSLOSnapshot());
        given(dependencyGraphProvider.fetchDependencies(SERVICE_NAME))
                .willReturn(List.of(aDependencyStatus()));
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of(aLogCluster()));
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput → data fetch → LLM assess → formatted card")
    void shouldExecuteFullGoapChain() {
        // given — stub all external ports
        stubAllPorts();

        // given — stub LLM to return fixture health report
        whenCreateObject(
                        prompt -> prompt.contains(SERVICE_NAME), ServiceHealthReport.class)
                .thenReturn(aServiceHealthReport());

        // when — run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform, ServiceHealthAgent.FormattedHealthCard.class);
        var result = invocation.invoke(new UserInput(SERVICE_NAME), agent);

        // then — verify the output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report().overallStatus()).isEqualTo(HealthStatus.AMBER);
        assertThat(result.markdown()).contains("Service Health");
        assertThat(result.markdown()).contains("AMBER");
        assertThat(result.markdown()).contains("SLI Summary");
        assertThat(result.markdown()).contains("SLO Budget");
        assertThat(result.markdown()).contains("Recommendation");

        // then — verify LLM was called with correct context
        verifyCreateObject(
                prompt -> prompt.contains(SERVICE_NAME) && prompt.contains("Assess"),
                ServiceHealthReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("All four data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // when
        agent.fetchSLIMetrics(query);
        agent.fetchSLOBudget(query);
        agent.fetchDependencyHealth(query);
        agent.fetchRecentErrors(query);

        // then
        then(metricsProvider).should().fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class));
        then(metricsProvider).should().fetchSLOBudget(SERVICE_NAME, "availability");
        then(dependencyGraphProvider).should().fetchDependencies(SERVICE_NAME);
        then(logSearchProvider)
                .should()
                .searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR"));
    }

    @Test
    @DisplayName("Formatted health card contains all expected sections")
    void shouldProduceFormattedHealthCardWithAllSections() {
        // given
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));
        var report = aServiceHealthReport();

        // when
        var result = agent.formatHealthCard(report, query);

        // then
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.markdown())
                .contains("Service Health: " + SERVICE_NAME)
                .contains("AMBER")
                .contains("error_rate")
                .contains("evaluator")
                .contains("SEV3")
                .contains("Monitor error rate");
    }

    @Test
    @DisplayName("SLI metrics action returns correct snapshot from Prometheus")
    void shouldFetchSLIMetricsFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // when
        var sliMetrics = agent.fetchSLIMetrics(query);

        // then
        assertThat(sliMetrics.snapshot().service()).isEqualTo(SERVICE_NAME);
        assertThat(sliMetrics.snapshot().errorRate()).isEqualTo(0.05);
        assertThat(sliMetrics.snapshot().latencyP99()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("SLO budget action returns burn rate from Prometheus")
    void shouldFetchSLOBudgetFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // when
        var sloBudget = agent.fetchSLOBudget(query);

        // then
        assertThat(sloBudget.sloName()).isEqualTo("availability-99.9");
        assertThat(sloBudget.budgetRemaining()).isEqualTo(55.0);
        assertThat(sloBudget.burnRate()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("Dependency health action returns statuses from service mesh")
    void shouldFetchDependencyHealthFromPrometheus() {
        // given
        stubAllPorts();
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // when
        var deps = agent.fetchDependencyHealth(query);

        // then
        assertThat(deps.statuses()).hasSize(1);
        assertThat(deps.statuses().getFirst().name()).isEqualTo("evaluator");
        assertThat(deps.statuses().getFirst().status()).isEqualTo(HealthStatus.GREEN);
    }

    @Test
    @DisplayName("Recent errors action returns log clusters from Loki")
    void shouldFetchRecentErrorsFromLoki() {
        // given
        stubAllPorts();
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // when
        var errors = agent.fetchRecentErrors(query);

        // then
        assertThat(errors.clusters()).hasSize(1);
        assertThat(errors.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(errors.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("Graceful handling when no dependencies are discovered")
    void shouldHandleEmptyDependencies() {
        // given
        given(dependencyGraphProvider.fetchDependencies(SERVICE_NAME)).willReturn(List.of());
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // when
        var deps = agent.fetchDependencyHealth(query);

        // then
        assertThat(deps.statuses()).isEmpty();
    }

    @Test
    @DisplayName("Graceful handling when no recent errors exist")
    void shouldHandleEmptyErrors() {
        // given
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class), eq("ERROR")))
                .willReturn(List.of());
        var query = agent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // when
        var errors = agent.fetchRecentErrors(query);

        // then
        assertThat(errors.clusters()).isEmpty();
    }
}
