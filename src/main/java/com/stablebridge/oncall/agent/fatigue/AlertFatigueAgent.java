package com.stablebridge.oncall.agent.fatigue;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.alert.AlertSummary;
import com.stablebridge.oncall.domain.model.fatigue.AlertFatigueReport;
import com.stablebridge.oncall.domain.port.pagerduty.AlertDatasetProvider;
import com.stablebridge.oncall.domain.service.AlertFatigueReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(description = "Analyze alert patterns to identify noise and reduce on-call fatigue")
@Slf4j
@RequiredArgsConstructor
public class AlertFatigueAgent {

    private final AlertDatasetProvider alertDatasetProvider;
    private final AlertFatigueReportFormatter alertFatigueReportFormatter;

    // Blackboard state records
    public record AlertFatigueQuery(String team, int days) {}

    public record AlertDataset(List<AlertSummary> alerts) {}

    public record OutcomeDataset(List<AlertSummary> outcomes) {}

    public record FormattedFatigueReport(
            String team, int days, AlertFatigueReport report, String markdown) {}

    @Action(description = "Parse alert fatigue request into team and time range")
    public AlertFatigueQuery parseRequest(UserInput userInput) {
        var content = userInput.getContent().trim();
        log.info("Parsing alert fatigue request: {}", content);

        String team;
        int days;
        var parts = content.split("\\s+", 2);
        if (parts.length >= 2) {
            team = parts[0];
            try {
                days = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                days = 7;
            }
        } else {
            team = content;
            days = 7;
        }

        return new AlertFatigueQuery(team, days);
    }

    @Action(description = "Fetch all alerts from PagerDuty for the team and time range")
    public AlertDataset fetchAllAlerts(AlertFatigueQuery query) {
        log.info(
                "Fetching all alerts for team: {} over {} days",
                query.team(),
                query.days());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(query.days()));
        var alerts = alertDatasetProvider.fetchAllAlerts(query.team(), from, to);
        return new AlertDataset(alerts);
    }

    @Action(description = "Fetch alert outcomes from PagerDuty for the team and time range")
    public OutcomeDataset fetchAlertOutcomes(AlertFatigueQuery query) {
        log.info(
                "Fetching alert outcomes for team: {} over {} days",
                query.team(),
                query.days());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(query.days()));
        var outcomes = alertDatasetProvider.fetchAlertOutcomes(query.team(), from, to);
        return new OutcomeDataset(outcomes);
    }

    @Action(
            description =
                    "Analyze alert fatigue using SRE Manager persona to identify noise and tuning opportunities")
    public AlertFatigueReport analyzeAlertFatigue(
            AlertDataset alertDataset,
            OutcomeDataset outcomeDataset,
            AlertFatigueQuery query,
            Ai ai) {
        log.info("Analyzing alert fatigue for team: {}", query.team());
        String prompt = buildAnalysisPrompt(query, alertDataset, outcomeDataset);
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.SRE_MANAGER)
                .creating(AlertFatigueReport.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted alert fatigue report",
            export =
                    @Export(
                            name = "analyzeAlertFatigue",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format alert fatigue report as markdown")
    public FormattedFatigueReport formatReport(
            AlertFatigueReport report, AlertFatigueQuery query) {
        log.info(
                "Formatting fatigue report for team: {} — noise: {}%",
                query.team(),
                String.format("%.1f", report.noisePercentage() * 100));
        String markdown =
                alertFatigueReportFormatter.format(query.team(), query.days(), report);
        return new FormattedFatigueReport(query.team(), query.days(), report, markdown);
    }

    private String buildAnalysisPrompt(
            AlertFatigueQuery query,
            AlertDataset alertDataset,
            OutcomeDataset outcomeDataset) {
        int totalAlerts = alertDataset.alerts().size();
        int totalOutcomes = outcomeDataset.outcomes().size();
        long autoResolved =
                outcomeDataset.outcomes().stream()
                        .filter(a -> a.resolvedAt() != null && a.ttResolve() != null)
                        .filter(a -> a.ttResolve().toMinutes() < 5)
                        .count();

        return String.format(
                """
                Analyze alert fatigue for team '%s' over the last %d days.

                ## Alert Dataset
                - Total Alerts: %d
                - Alert Outcomes: %d
                - Auto-resolved (< 5 min): %d

                ## Alerts
                %s

                ## Outcomes
                %s

                Provide your analysis as an AlertFatigueReport with:
                1. totalAlerts: total number of alerts in the period
                2. noisePercentage: fraction of alerts that are noise (0.0 to 1.0)
                3. topNoisyRules: list of the noisiest alert rules with rule name, service, count, auto-resolve rate, and recommendation
                4. duplicateGroups: groups of duplicate alerts that could be consolidated
                5. tuningRecommendations: specific tuning actions with rule, action, expected reduction percentage, and priority (HIGH/MEDIUM/LOW)
                6. summary: ONE sentence summarizing the key finding and recommended action
                """,
                query.team(),
                query.days(),
                totalAlerts,
                totalOutcomes,
                autoResolved,
                formatAlerts(alertDataset.alerts()),
                formatAlerts(outcomeDataset.outcomes()));
    }

    private String formatAlerts(List<AlertSummary> alerts) {
        if (alerts.isEmpty()) {
            return "No alerts found.";
        }
        var sb = new StringBuilder();
        for (var alert : alerts) {
            sb.append(
                    String.format(
                            "- %s: %s [%s] triggered=%s resolved=%s%n",
                            alert.alertId(),
                            alert.description(),
                            alert.status(),
                            alert.triggeredAt(),
                            alert.resolvedAt() != null ? alert.resolvedAt() : "N/A"));
        }
        return sb.toString();
    }
}
