package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.FormattedPostMortem;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.postmortem.PostMortemDraft;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static com.stablebridge.oncall.fixtures.PostMortemFixtures.aPostMortemDraft;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class PostMortemE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String INCIDENT_ID = "INC-E2E-001";
    private static final String TARGET_SERVICE = "alert-api";

    @Autowired
    private PostMortemAgent postMortemAgent;

    @Autowired
    private MetricsProvider metricsProvider;

    @Autowired
    private LogSearchProvider logSearchProvider;

    @Test
    @DisplayName(
            "E2E: Post-mortem draft generation produces report using live Prometheus + Loki with mock PagerDuty + ArgoCD")
    void shouldProducePostMortemFromLiveData() {
        // given — stub LLM to return a post-mortem draft
        whenCreateObject(prompt -> prompt.contains(INCIDENT_ID), PostMortemDraft.class)
                .thenReturn(aPostMortemDraft());

        // when — run agent with real Prometheus + Loki, mock PagerDuty + ArgoCD
        var input = INCIDENT_ID + " " + TARGET_SERVICE;
        var invocation =
                AgentInvocation.create(agentPlatform, FormattedPostMortem.class);
        var result = invocation.invoke(new UserInput(input), postMortemAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(result.draft()).isNotNull();
        assertThat(result.draft().severity()).isNotNull();
        assertThat(result.draft().rootCause()).isNotBlank();
        assertThat(result.draft().actionItems()).isNotEmpty();
        assertThat(result.markdown()).isNotBlank();

        // then — verify LLM was called (meaning all data was fetched successfully)
        verifyCreateObject(
                prompt -> prompt.contains(INCIDENT_ID) && prompt.contains("post-mortem"),
                PostMortemDraft.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("E2E: Live adapters return data for post-mortem data-fetch actions")
    void shouldFetchRealDataFromPrometheusAndLoki() {
        // when + then — Prometheus returns metrics for the service
        var metrics =
                metricsProvider.fetchServiceMetrics(TARGET_SERVICE, Instant.now());
        assertThat(metrics).isNotNull();
        assertThat(metrics.service()).isEqualTo(TARGET_SERVICE);

        // when + then — Loki returns logs (may be empty)
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(4));
        var logs = logSearchProvider.searchLogs(TARGET_SERVICE, from, to, "ERROR");
        assertThat(logs).isNotNull();
    }
}
