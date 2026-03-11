package com.stablebridge.oncall.agent.fatigue;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.fatigue.AlertFatigueReport;
import com.stablebridge.oncall.domain.port.pagerduty.AlertDatasetProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.AlertFixtures.anAlertSummary;
import static com.stablebridge.oncall.fixtures.FatigueFixtures.anAlertFatigueReport;
import static com.stablebridge.oncall.fixtures.TestConstants.TEAM_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@Import(TestJacksonConfig.class)
class AlertFatigueAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private AlertDatasetProvider alertDatasetProvider;

    private AlertFatigueAgent agent;

    @BeforeEach
    void setUpMocks() {
        alertDatasetProvider = Mockito.mock(AlertDatasetProvider.class);

        agent =
                new AlertFatigueAgent(
                        alertDatasetProvider,
                        new com.stablebridge.oncall.domain.service
                                .AlertFatigueReportFormatter());
    }

    private void stubAllPorts() {
        given(alertDatasetProvider.fetchAllAlerts(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of(anAlertSummary()));
        given(alertDatasetProvider.fetchAlertOutcomes(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of(anAlertSummary()));
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput -> data fetch -> LLM analyze -> formatted report")
    void shouldExecuteFullGoapChain() {
        // given - stub all external ports
        stubAllPorts();

        // given - stub LLM to return fixture fatigue report
        whenCreateObject(prompt -> prompt.contains(TEAM_NAME), AlertFatigueReport.class)
                .thenReturn(anAlertFatigueReport());

        // when - run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform,
                        AlertFatigueAgent.FormattedFatigueReport.class);
        var result = invocation.invoke(new UserInput(TEAM_NAME + " 7"), agent);

        // then - verify the output
        assertThat(result).isNotNull();
        assertThat(result.team()).isEqualTo(TEAM_NAME);
        assertThat(result.days()).isEqualTo(7);
        assertThat(result.report().noisePercentage()).isEqualTo(0.65);
        assertThat(result.report().totalAlerts()).isEqualTo(120);
        assertThat(result.markdown()).contains("Alert Fatigue Report");
        assertThat(result.markdown()).contains(TEAM_NAME);
        assertThat(result.markdown()).contains("Noisy Rules");
        assertThat(result.markdown()).contains("Tuning Recommendations");
        assertThat(result.markdown()).contains("Summary");

        // then - verify LLM was called with correct context
        verifyCreateObject(
                prompt -> prompt.contains(TEAM_NAME) && prompt.contains("Analyze"),
                AlertFatigueReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("Both data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query = agent.parseRequest(new UserInput(TEAM_NAME + " 7"));

        // when
        agent.fetchAllAlerts(query);
        agent.fetchAlertOutcomes(query);

        // then
        then(alertDatasetProvider)
                .should()
                .fetchAllAlerts(eq(TEAM_NAME), any(Instant.class), any(Instant.class));
        then(alertDatasetProvider)
                .should()
                .fetchAlertOutcomes(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class));
    }

    @Test
    @DisplayName("Formatted fatigue report contains all expected sections")
    void shouldProduceFormattedReportWithAllSections() {
        // given
        var query = agent.parseRequest(new UserInput(TEAM_NAME + " 7"));
        var report = anAlertFatigueReport();

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.team()).isEqualTo(TEAM_NAME);
        assertThat(result.days()).isEqualTo(7);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.markdown())
                .contains("Alert Fatigue Report: " + TEAM_NAME)
                .contains("Total Alerts")
                .contains("120")
                .contains("HighCPUWarning")
                .contains("tick-ingestor")
                .contains("INCREASE_THRESHOLD")
                .contains("65%");
    }

    @Test
    @DisplayName("Alert dataset action returns correct summaries from PagerDuty")
    void shouldFetchAlertDatasetFromPagerDuty() {
        // given
        stubAllPorts();
        var query = agent.parseRequest(new UserInput(TEAM_NAME + " 7"));

        // when
        var alertDataset = agent.fetchAllAlerts(query);

        // then
        assertThat(alertDataset.alerts()).hasSize(1);
        assertThat(alertDataset.alerts().getFirst().alertId()).isEqualTo("ALT-001");
    }

    @Test
    @DisplayName("Outcome dataset action returns correct summaries from PagerDuty")
    void shouldFetchOutcomeDatasetFromPagerDuty() {
        // given
        stubAllPorts();
        var query = agent.parseRequest(new UserInput(TEAM_NAME + " 7"));

        // when
        var outcomeDataset = agent.fetchAlertOutcomes(query);

        // then
        assertThat(outcomeDataset.outcomes()).hasSize(1);
        assertThat(outcomeDataset.outcomes().getFirst().alertId()).isEqualTo("ALT-001");
    }

    @Test
    @DisplayName("Graceful handling when no alerts are found")
    void shouldHandleEmptyAlerts() {
        // given
        given(alertDatasetProvider.fetchAllAlerts(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());
        var query = agent.parseRequest(new UserInput(TEAM_NAME + " 7"));

        // when
        var alertDataset = agent.fetchAllAlerts(query);

        // then
        assertThat(alertDataset.alerts()).isEmpty();
    }

    @Test
    @DisplayName("Graceful handling when no outcomes are found")
    void shouldHandleEmptyOutcomes() {
        // given
        given(alertDatasetProvider.fetchAlertOutcomes(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());
        var query = agent.parseRequest(new UserInput(TEAM_NAME + " 7"));

        // when
        var outcomeDataset = agent.fetchAlertOutcomes(query);

        // then
        assertThat(outcomeDataset.outcomes()).isEmpty();
    }
}
