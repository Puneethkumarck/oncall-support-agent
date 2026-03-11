package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent.FormattedHealthCard;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.health.ServiceHealthReport;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static com.stablebridge.oncall.fixtures.HealthFixtures.aServiceHealthReport;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class ServiceHealthE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String TARGET_SERVICE = "alert-api";

    @Autowired
    private ServiceHealthAgent serviceHealthAgent;

    @Autowired
    private MetricsProvider metricsProvider;

    @Test
    @DisplayName("E2E: Health check on alert-api produces meaningful health card from live Prometheus data")
    void shouldProduceHealthCardFromLiveData() {
        // given — stub LLM to return a health report
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_SERVICE), ServiceHealthReport.class)
                .thenReturn(aServiceHealthReport());

        // when — run agent with real Prometheus + Loki adapters
        var invocation = AgentInvocation.create(agentPlatform, FormattedHealthCard.class);
        var result = invocation.invoke(new UserInput(TARGET_SERVICE), serviceHealthAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.service()).isEqualTo(TARGET_SERVICE);
        assertThat(result.report()).isNotNull();
        assertThat(result.report().overallStatus()).isNotNull();
        assertThat(result.markdown()).isNotBlank();
        assertThat(result.markdown()).contains("Service Health");

        // then — verify LLM was called (meaning real data was fetched successfully)
        verifyCreateObject(
                prompt -> prompt.contains(TARGET_SERVICE) && prompt.contains("Assess"),
                ServiceHealthReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("E2E: Prometheus adapter returns real metrics for alert-api")
    void shouldFetchRealMetricsFromPrometheus() {
        // when — call Prometheus directly
        var metrics = metricsProvider.fetchServiceMetrics(TARGET_SERVICE, Instant.now());

        // then — verify we got a response (values may vary)
        assertThat(metrics).isNotNull();
        assertThat(metrics.service()).isEqualTo(TARGET_SERVICE);
    }
}
