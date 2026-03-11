package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.FormattedTriageReport;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.alert.IncidentAssessment;
import com.stablebridge.oncall.domain.port.grafana.DashboardProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static com.stablebridge.oncall.fixtures.AlertFixtures.anIncidentAssessment;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class IncidentTriageE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String TARGET_SERVICE = "alert-api";

    @Autowired
    private IncidentTriageAgent incidentTriageAgent;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private LogSearchProvider logSearchProvider;

    @Autowired
    private DashboardProvider dashboardProvider;

    @Test
    @DisplayName("E2E: Full triage with simulated alert produces report from live observability data")
    void shouldProduceTriageReportFromLiveData() {
        // given — stub LLM to return an incident assessment
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_SERVICE), IncidentAssessment.class)
                .thenReturn(anIncidentAssessment());

        // when — run agent with real Prometheus + Loki + Grafana adapters
        // Format: service|severity|description
        var input = TARGET_SERVICE + "|SEV2|High error rate on alert-api";
        var invocation = AgentInvocation.create(agentPlatform, FormattedTriageReport.class);
        var result = invocation.invoke(new UserInput(input), incidentTriageAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.alert()).isNotNull();
        assertThat(result.alert().service()).isEqualTo(TARGET_SERVICE);
        assertThat(result.assessment()).isNotNull();
        assertThat(result.assessment().likelyCause()).isNotBlank();
        assertThat(result.markdown()).isNotBlank();

        // then — verify LLM was called (meaning all data was fetched from live services)
        verifyCreateObject(
                prompt -> prompt.contains(TARGET_SERVICE) && prompt.contains("Triage"),
                IncidentAssessment.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("E2E: Live adapters return data for triage data-fetch actions")
    void shouldFetchRealDataFromAllObservabilityPorts() {
        // given
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(30));

        // when + then — Prometheus returns metrics
        var metrics = metricsProvider.fetchServiceMetrics(TARGET_SERVICE, to);
        assertThat(metrics).isNotNull();
        assertThat(metrics.service()).isEqualTo(TARGET_SERVICE);

        // when + then — Loki returns logs (may be empty)
        var logs = logSearchProvider.searchLogs(TARGET_SERVICE, from, to, "ERROR");
        assertThat(logs).isNotNull();

        // when + then — Grafana returns annotations (may be empty)
        var annotations = dashboardProvider.fetchAnnotations(TARGET_SERVICE, from, to);
        assertThat(annotations).isNotNull();
    }
}
