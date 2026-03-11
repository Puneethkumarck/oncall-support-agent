package com.stablebridge.oncall.agent.fatigue;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent.AlertDataset;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent.AlertFatigueQuery;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent.OutcomeDataset;
import com.stablebridge.oncall.domain.port.pagerduty.AlertDatasetProvider;
import com.stablebridge.oncall.domain.service.AlertFatigueReportFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.AlertFixtures.anAlertSummary;
import static com.stablebridge.oncall.fixtures.FatigueFixtures.anAlertFatigueReport;
import static com.stablebridge.oncall.fixtures.TestConstants.TEAM_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AlertFatigueAgentTest {

    @Mock private AlertDatasetProvider alertDatasetProvider;
    @Mock private AlertFatigueReportFormatter alertFatigueReportFormatter;

    private AlertFatigueAgent agent;

    @BeforeEach
    void setUp() {
        agent = new AlertFatigueAgent(alertDatasetProvider, alertFatigueReportFormatter);
    }

    @Test
    @DisplayName("parseRequest extracts team and days from UserInput")
    void shouldParseRequest() {
        // given
        var userInput = new UserInput(TEAM_NAME + " 14");

        // when
        var result = agent.parseRequest(userInput);

        // then
        assertThat(result.team()).isEqualTo(TEAM_NAME);
        assertThat(result.days()).isEqualTo(14);
    }

    @Test
    @DisplayName("parseRequest defaults to 7 days when only team is provided")
    void shouldDefaultToSevenDays() {
        // given
        var userInput = new UserInput(TEAM_NAME);

        // when
        var result = agent.parseRequest(userInput);

        // then
        assertThat(result.team()).isEqualTo(TEAM_NAME);
        assertThat(result.days()).isEqualTo(7);
    }

    @Test
    @DisplayName("parseRequest trims whitespace from input")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + TEAM_NAME + "  ");

        // when
        var result = agent.parseRequest(userInput);

        // then
        assertThat(result.team()).isEqualTo(TEAM_NAME);
        assertThat(result.days()).isEqualTo(7);
    }

    @Test
    @DisplayName("parseRequest defaults to 7 days when days is not a number")
    void shouldDefaultWhenDaysNotNumeric() {
        // given
        var userInput = new UserInput(TEAM_NAME + " abc");

        // when
        var result = agent.parseRequest(userInput);

        // then
        assertThat(result.team()).isEqualTo(TEAM_NAME);
        assertThat(result.days()).isEqualTo(7);
    }

    @Test
    @DisplayName("fetchAllAlerts returns alert summaries from PagerDuty")
    void shouldFetchAllAlerts() {
        // given
        var query = new AlertFatigueQuery(TEAM_NAME, 7);
        var expectedAlerts = List.of(anAlertSummary());
        given(alertDatasetProvider.fetchAllAlerts(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(expectedAlerts);

        // when
        var result = agent.fetchAllAlerts(query);

        // then
        assertThat(result.alerts()).hasSize(1);
        assertThat(result.alerts().getFirst().alertId()).isEqualTo("ALT-001");
    }

    @Test
    @DisplayName("fetchAllAlerts returns empty list when no alerts found")
    void shouldReturnEmptyAlertsWhenNone() {
        // given
        var query = new AlertFatigueQuery(TEAM_NAME, 7);
        given(alertDatasetProvider.fetchAllAlerts(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());

        // when
        var result = agent.fetchAllAlerts(query);

        // then
        assertThat(result.alerts()).isEmpty();
    }

    @Test
    @DisplayName("fetchAlertOutcomes returns outcome summaries from PagerDuty")
    void shouldFetchAlertOutcomes() {
        // given
        var query = new AlertFatigueQuery(TEAM_NAME, 7);
        var expectedOutcomes = List.of(anAlertSummary());
        given(alertDatasetProvider.fetchAlertOutcomes(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(expectedOutcomes);

        // when
        var result = agent.fetchAlertOutcomes(query);

        // then
        assertThat(result.outcomes()).hasSize(1);
        assertThat(result.outcomes().getFirst().alertId()).isEqualTo("ALT-001");
    }

    @Test
    @DisplayName("fetchAlertOutcomes returns empty list when no outcomes found")
    void shouldReturnEmptyOutcomesWhenNone() {
        // given
        var query = new AlertFatigueQuery(TEAM_NAME, 7);
        given(alertDatasetProvider.fetchAlertOutcomes(
                        eq(TEAM_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());

        // when
        var result = agent.fetchAlertOutcomes(query);

        // then
        assertThat(result.outcomes()).isEmpty();
    }

    @Test
    @DisplayName("formatReport delegates to AlertFatigueReportFormatter and wraps result")
    void shouldFormatReport() {
        // given
        var report = anAlertFatigueReport();
        var query = new AlertFatigueQuery(TEAM_NAME, 7);
        var expectedMarkdown = "# Alert Fatigue Report: platform (last 7 days)";
        given(alertFatigueReportFormatter.format(TEAM_NAME, 7, report))
                .willReturn(expectedMarkdown);

        // when
        var result = agent.formatReport(report, query);

        // then
        assertThat(result.team()).isEqualTo(TEAM_NAME);
        assertThat(result.days()).isEqualTo(7);
        assertThat(result.report()).isEqualTo(report);
        assertThat(result.report().noisePercentage()).isEqualTo(0.65);
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(alertFatigueReportFormatter).format(TEAM_NAME, 7, report);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var alerts = List.of(anAlertSummary());
        var outcomes = List.of(anAlertSummary());

        // when
        var alertDataset = new AlertDataset(alerts);
        var outcomeDataset = new OutcomeDataset(outcomes);

        // then
        assertThat(alertDataset.alerts()).isEqualTo(alerts);
        assertThat(outcomeDataset.outcomes()).isEqualTo(outcomes);
    }
}
