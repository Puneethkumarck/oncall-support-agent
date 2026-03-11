package com.stablebridge.oncall.infrastructure.pagerduty;

import com.stablebridge.oncall.domain.model.alert.AlertHistorySnapshot;
import com.stablebridge.oncall.domain.model.alert.AlertSummary;
import com.stablebridge.oncall.domain.model.common.AlertStatus;
import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.postmortem.TimelineEntry;
import com.stablebridge.oncall.domain.port.pagerduty.AlertHistoryProvider;
import com.stablebridge.oncall.domain.port.pagerduty.IncidentTimelineProvider;
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
class PagerDutyHistoryAdapter implements AlertHistoryProvider, IncidentTimelineProvider {

    private final WebClient pagerdutyWebClient;

    @Override
    public AlertHistorySnapshot fetchAlertHistory(String service, Instant from, Instant to) {
        log.info(
                "Fetching PagerDuty alert history for service={} from={} to={}",
                service,
                from,
                to);

        JsonNode json;
        try {
            json =
                    pagerdutyWebClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/incidents")
                                                    .queryParam("service_ids[]", "{service}")
                                                    .queryParam("since", "{since}")
                                                    .queryParam("until", "{until}")
                                                    .build(service, from.toString(), to.toString()))
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is4xxClientError,
                                    response -> {
                                        throw new ResourceNotFoundException(
                                                "PagerDuty incidents not found for service: "
                                                        + service);
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
                        "PagerDuty incidents not found for service: " + service);
            }
            throw new ServiceUnavailableException(
                    "PagerDuty service error: HTTP " + e.getStatusCode().value());
        }

        return parseAlertHistory(service, json);
    }

    @Override
    public List<TimelineEntry> fetchIncidentTimeline(String incidentId) {
        log.info("Fetching PagerDuty incident timeline for incidentId={}", incidentId);

        JsonNode json;
        try {
            json =
                    pagerdutyWebClient
                            .get()
                            .uri("/incidents/{incidentId}/log_entries", incidentId)
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is4xxClientError,
                                    response -> {
                                        throw new ResourceNotFoundException(
                                                "PagerDuty incident not found: " + incidentId);
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
                        "PagerDuty incident not found: " + incidentId);
            }
            throw new ServiceUnavailableException(
                    "PagerDuty service error: HTTP " + e.getStatusCode().value());
        }

        if (json == null) {
            return List.of();
        }

        return parseLogEntries(json);
    }

    private AlertHistorySnapshot parseAlertHistory(String service, JsonNode json) {
        if (json == null) {
            return new AlertHistorySnapshot(service, 0, List.of(), false, null);
        }

        JsonNode incidents = json.get("incidents");
        if (incidents == null || !incidents.isArray()) {
            return new AlertHistorySnapshot(service, 0, List.of(), false, null);
        }

        List<AlertSummary> recentAlerts = new ArrayList<>();
        Instant lastOccurrence = null;

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

            recentAlerts.add(
                    new AlertSummary(id, description, status, triggeredAt, resolvedAt, ttResolve));

            if (lastOccurrence == null || triggeredAt.isAfter(lastOccurrence)) {
                lastOccurrence = triggeredAt;
            }
        }

        int totalAlerts = recentAlerts.size();
        boolean isRecurring = totalAlerts > 1;

        return new AlertHistorySnapshot(
                service, totalAlerts, recentAlerts, isRecurring, lastOccurrence);
    }

    private List<TimelineEntry> parseLogEntries(JsonNode json) {
        List<TimelineEntry> entries = new ArrayList<>();

        JsonNode logEntries = json.get("log_entries");
        if (logEntries == null || !logEntries.isArray()) {
            return entries;
        }

        for (JsonNode logEntry : logEntries) {
            Instant timestamp = Instant.parse(logEntry.get("created_at").asText());
            String event = logEntry.has("type") ? logEntry.get("type").asText() : "unknown";
            String actor = "system";
            if (logEntry.has("agent")) {
                JsonNode agentNode = logEntry.get("agent");
                if (agentNode.has("summary")) {
                    actor = agentNode.get("summary").asText();
                }
            }

            entries.add(new TimelineEntry(timestamp, event, actor));
        }

        return entries;
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
