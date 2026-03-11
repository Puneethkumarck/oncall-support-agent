package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.FormattedLogAnalysis;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.logs.LogAnalysisReport;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;

import static com.stablebridge.oncall.fixtures.LogFixtures.aLogAnalysisReport;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class LogAnalysisE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String TARGET_SERVICE = "tick-ingestor";

    @Autowired
    private LogAnalysisAgent logAnalysisAgent;

    @Autowired
    private LogSearchProvider logSearchProvider;

    @Test
    @DisplayName("E2E: Log analysis on tick-ingestor produces report from live Loki data")
    void shouldProduceLogAnalysisFromLiveData() {
        // given — stub LLM to return a log analysis report
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_SERVICE), LogAnalysisReport.class)
                .thenReturn(aLogAnalysisReport());

        // when — run agent with real Loki + Prometheus adapters
        var invocation = AgentInvocation.create(agentPlatform, FormattedLogAnalysis.class);
        var result = invocation.invoke(new UserInput(TARGET_SERVICE), logAnalysisAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.query().service()).isEqualTo(TARGET_SERVICE);
        assertThat(result.report()).isNotNull();
        assertThat(result.formattedBody()).isNotBlank();
        assertThat(result.formattedBody()).contains("Log Analysis");

        // then — verify LLM was called (meaning real data was fetched successfully)
        verifyCreateObject(
                prompt -> prompt.contains(TARGET_SERVICE) && prompt.contains("Analyze"),
                LogAnalysisReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("E2E: Loki adapter returns real log data for tick-ingestor")
    void shouldFetchRealLogsFromLoki() {
        // when — call Loki directly
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(30));
        var clusters = logSearchProvider.searchLogs(TARGET_SERVICE, from, to, "ERROR");

        // then — verify we got a response (may be empty if no errors)
        assertThat(clusters).isNotNull();
    }
}
