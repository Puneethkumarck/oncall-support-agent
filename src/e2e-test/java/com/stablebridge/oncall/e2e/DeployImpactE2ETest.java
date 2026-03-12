package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.FormattedDeployImpact;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.deploy.DeployImpactReport;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeployImpactReport;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class DeployImpactE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String TARGET_SERVICE = "evaluator";

    @Autowired
    private DeployImpactAgent deployImpactAgent;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private LogSearchProvider logSearchProvider;

    @Test
    @DisplayName("E2E: Deploy impact analysis produces report using live Prometheus + Loki data with mock ArgoCD")
    void shouldProduceDeployImpactFromLiveData() {
        // given — stub LLM to return a deploy impact report
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_SERVICE), DeployImpactReport.class)
                .thenReturn(aDeployImpactReport());

        // when — run agent with real Prometheus + Loki adapters, mock ArgoCD
        var invocation = AgentInvocation.create(agentPlatform, FormattedDeployImpact.class);
        var result = invocation.invoke(new UserInput(TARGET_SERVICE), deployImpactAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(TARGET_SERVICE);
        assertThat(result.report()).isNotNull();
        assertThat(result.report().rollbackRecommendation()).isNotNull();
        assertThat(result.markdown()).isNotBlank();

        // then — verify LLM was called (meaning all data was fetched successfully)
        verifyCreateObject(
                prompt -> prompt.contains(TARGET_SERVICE) && prompt.contains("Analyze"),
                DeployImpactReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("E2E: Prometheus returns pre/post deploy metrics for evaluator")
    void shouldFetchPrePostMetricsFromPrometheus() {
        // when — fetch current metrics (simulating post-deploy)
        var postMetrics =
                metricsProvider.fetchServiceMetrics(TARGET_SERVICE, Instant.now());

        // then — verify we got a response
        assertThat(postMetrics).isNotNull();
        assertThat(postMetrics.service()).isEqualTo(TARGET_SERVICE);

        // when — fetch metrics from 30 minutes ago (simulating pre-deploy)
        var preMetrics =
                metricsProvider.fetchServiceMetrics(
                        TARGET_SERVICE,
                        Instant.now().minus(Duration.ofMinutes(30)));

        // then — verify we got a response
        assertThat(preMetrics).isNotNull();
        assertThat(preMetrics.service()).isEqualTo(TARGET_SERVICE);
    }

    @Test
    @DisplayName("E2E: Loki returns error logs for evaluator in post-deploy window")
    void shouldFetchNewErrorsFromLoki() {
        // when — call Loki directly for errors in last 30 minutes
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(30));
        var errors = logSearchProvider.searchLogs(TARGET_SERVICE, from, to, "ERROR");

        // then — verify we got a response (may be empty if no errors)
        assertThat(errors).isNotNull();
    }
}
