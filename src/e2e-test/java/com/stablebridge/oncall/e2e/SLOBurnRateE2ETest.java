package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.FormattedSLOReport;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.slo.SLOReport;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.stablebridge.oncall.fixtures.MetricsFixtures.anSLOReport;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class SLOBurnRateE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String TARGET_SERVICE = "evaluator";

    @Autowired
    private SLOMonitorAgent sloMonitorAgent;

    @Autowired
    private MetricsProvider metricsProvider;

    @Test
    @DisplayName("E2E: SLO burn rate on evaluator produces report from live Prometheus data")
    void shouldProduceSLOReportFromLiveData() {
        // given — stub LLM to return an SLO report
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_SERVICE), SLOReport.class)
                .thenReturn(anSLOReport());

        // when — run agent with real Prometheus adapter
        var invocation = AgentInvocation.create(agentPlatform, FormattedSLOReport.class);
        var result = invocation.invoke(new UserInput(TARGET_SERVICE), sloMonitorAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(TARGET_SERVICE);
        assertThat(result.report()).isNotNull();
        assertThat(result.report().sloStatus()).isNotNull();
        assertThat(result.markdown()).isNotBlank();
        assertThat(result.markdown()).contains("SLO");

        // then — verify LLM was called (meaning real data was fetched successfully)
        verifyCreateObject(
                prompt -> prompt.contains(TARGET_SERVICE) && prompt.contains("Analyze"),
                SLOReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("E2E: Prometheus adapter returns real SLO budget for evaluator")
    void shouldFetchRealSLOBudgetFromPrometheus() {
        // when — call Prometheus directly
        var budget = metricsProvider.fetchSLOBudget(TARGET_SERVICE, "availability");

        // then — verify we got a response
        assertThat(budget).isNotNull();
        assertThat(budget.sloName()).isNotBlank();
    }

    @Test
    @DisplayName("E2E: Prometheus adapter returns real burn contributors for evaluator")
    void shouldFetchRealBurnContributorsFromPrometheus() {
        // when — call Prometheus directly
        var contributors = metricsProvider.fetchBurnContributors(
                TARGET_SERVICE,
                java.time.Instant.now().minus(java.time.Duration.ofHours(1)),
                java.time.Instant.now());

        // then — verify we got a response (may be empty if no burn)
        assertThat(contributors).isNotNull();
    }
}
