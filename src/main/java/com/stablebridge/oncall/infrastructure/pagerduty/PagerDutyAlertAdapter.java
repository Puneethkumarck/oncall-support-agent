package com.stablebridge.oncall.infrastructure.pagerduty;

import com.stablebridge.oncall.domain.model.alert.AlertContext;
import com.stablebridge.oncall.domain.model.alert.AlertSummary;
import com.stablebridge.oncall.domain.model.common.AlertStatus;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.port.pagerduty.AlertDatasetProvider;
import com.stablebridge.oncall.domain.port.pagerduty.AlertProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.services.pagerduty.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PagerDutyAlertAdapter implements AlertProvider, AlertDatasetProvider {

    private final WebClient pagerdutyWebClient;

    @Override
    public AlertContext fetchAlert(String alertId) {
        log.info("Fetching PagerDuty incident for alertId={}", alertId);

        JsonNode json;
        try {
            json =
                    pagerdutyWebClient
                            .get()
                            .uri("/incidents/{alertId}", alertId)
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is4xxClientError,
                                    response -> {
                                        throw new ResourceNotFoundException(
                                                "PagerDuty incident not found: " + alertId);
                                    })
                            .onStatus(
                                    HttpStatusCode::is5xxServerError,
                                    response -> {
                                        throw new ServiceUnavailableException(
                                                "PagerDuty service error: HTTP "
                                                        + response.statusCode().value());
                                    })
                            .bodyToMono(JsonNode.class)
                            .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "PagerDuty incident not found: " + alertId);
            }
            throw new ServiceUnavailableException(
                    "PagerDuty service error: HTTP " + e.getStatusCode().value());
        }

        if (json == null) {
            throw new ResourceNotFoundException("PagerDuty incident not found: " + alertId);
        }

        return parseIncident(json.get("incident"));
    }

    @Override
    public List<AlertSummary> fetchAllAlerts(String team, Instant from, Instant to) {
        log.info(
                "Fetching all PagerDuty incidents for team={} from={} to={}",
                team,
                from,
                to);

        JsonNode json = fetchIncidents(team, from, to, null);
        return parseIncidentList(json);
    }

    @Override
    public List<AlertSummary> fetchAlertOutcomes(String team, Instant from, Instant to) {
        log.info(
                "Fetching resolved PagerDuty incidents for team={} from={} to={}",
                team,
                from,
                to);

        JsonNode json = fetchIncidents(team, from, to, "resolved");
        return parseIncidentList(json);
    }

    private JsonNode fetchIncidents(String team, Instant from, Instant to, String status) {
        try {
            return pagerdutyWebClient
                    .get()
                    .uri(
                            uriBuilder -> {
                                var builder =
                                        uriBuilder
                                                .path("/incidents")
                                                .queryParam("team_ids[]", "{team}")
                                                .queryParam("since", "{since}")
                                                .queryParam("until", "{until}");
                                if (status != null) {
                                    builder.queryParam("statuses[]", "{status}");
                                    return builder.build(team, from.toString(), to.toString(), status);
                                }
                                return builder.build(team, from.toString(), to.toString());
                            })
                    .retrieve()
                    .onStatus(
                            HttpStatusCode::is4xxClientError,
                            response -> {
                                throw new ResourceNotFoundException(
                                        "PagerDuty incidents not found for team: " + team);
                            })
                    .onStatus(
                            HttpStatusCode::is5xxServerError,
                            response -> {
                                throw new ServiceUnavailableException(
                                        "PagerDuty service error: HTTP "
                                                + response.statusCode().value());
                            })
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "PagerDuty incidents not found for team: " + team);
            }
            throw new ServiceUnavailableException(
                    "PagerDuty service error: HTTP " + e.getStatusCode().value());
        }
    }

    private AlertContext parseIncident(JsonNode incident) {
        String id = incident.get("id").asText();
        String service = incident.has("service")
                ? incident.get("service").get("summary").asText()
                : "unknown";
        String urgency = incident.has("urgency") ? incident.get("urgency").asText() : "low";
        IncidentSeverity severity = mapUrgency(urgency);
        String description =
                incident.has("description") ? incident.get("description").asText() : "";
        Instant createdAt = Instant.parse(incident.get("created_at").asText());
        String runbookUrl =
                incident.has("html_url") ? incident.get("html_url").asText() : null;
        String dedupKey =
                incident.has("incident_key") ? incident.get("incident_key").asText() : null;

        return new AlertContext(id, service, severity, description, createdAt, runbookUrl, dedupKey);
    }

    private List<AlertSummary> parseIncidentList(JsonNode json) {
        List<AlertSummary> summaries = new ArrayList<>();

        if (json == null) {
            return summaries;
        }

        JsonNode incidents = json.get("incidents");
        if (incidents == null || !incidents.isArray()) {
            return summaries;
        }

        for (JsonNode incident : incidents) {
            String id = incident.get("id").asText();
            String description =
                    incident.has("description") ? incident.get("description").asText() : "";
            AlertStatus status = mapStatus(
                    incident.has("status") ? incident.get("status").asText() : "triggered");
            Instant triggeredAt = Instant.parse(incident.get("created_at").asText());

            Instant resolvedAt = null;
            if (incident.has("last_status_change_at")
                    && status == AlertStatus.RESOLVED) {
                resolvedAt = Instant.parse(incident.get("last_status_change_at").asText());
            }

            Duration ttResolve = null;
            if (resolvedAt != null) {
                ttResolve = Duration.between(triggeredAt, resolvedAt);
            }

            summaries.add(
                    new AlertSummary(id, description, status, triggeredAt, resolvedAt, ttResolve));
        }

        return summaries;
    }

    private IncidentSeverity mapUrgency(String urgency) {
        return switch (urgency) {
            case "high" -> IncidentSeverity.SEV2;
            case "low" -> IncidentSeverity.SEV4;
            default -> IncidentSeverity.SEV3;
        };
    }

    private AlertStatus mapStatus(String status) {
        return switch (status) {
            case "triggered" -> AlertStatus.TRIGGERED;
            case "acknowledged" -> AlertStatus.ACKNOWLEDGED;
            case "resolved" -> AlertStatus.RESOLVED;
            case "suppressed" -> AlertStatus.SUPPRESSED;
            default -> AlertStatus.TRIGGERED;
        };
    }
}
