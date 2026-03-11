package com.stablebridge.oncall.infrastructure.grafana;

import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.port.grafana.DashboardProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.services.grafana.enabled",
        havingValue = "true",
        matchIfMissing = false)
class GrafanaAdapter implements DashboardProvider {

    private final WebClient grafanaWebClient;

    @Override
    public List<String> fetchAnnotations(String dashboardUid, Instant from, Instant to) {
        log.info(
                "Fetching Grafana annotations for dashboard={} from={} to={}",
                dashboardUid,
                from,
                to);

        JsonNode json;
        try {
            json =
                    grafanaWebClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/api/annotations")
                                                    .queryParam("dashboardUID", "{dashboardUid}")
                                                    .queryParam("from", "{from}")
                                                    .queryParam("to", "{to}")
                                                    .build(
                                                            dashboardUid,
                                                            String.valueOf(from.toEpochMilli()),
                                                            String.valueOf(to.toEpochMilli())))
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is5xxServerError,
                                    response -> {
                                        throw new ServiceUnavailableException(
                                                "Grafana service error: HTTP "
                                                        + response.statusCode().value());
                                    })
                            .bodyToMono(JsonNode.class)
                            .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.warn(
                        "Grafana returned {} — returning empty list",
                        e.getStatusCode().value());
                return List.of();
            }
            throw new ServiceUnavailableException(
                    "Grafana service error: HTTP " + e.getStatusCode().value());
        }

        if (json == null || !json.isArray()) {
            return List.of();
        }

        List<String> annotations = new ArrayList<>();
        for (JsonNode annotation : json) {
            JsonNode textNode = annotation.get("text");
            if (textNode != null && !textNode.asText().isBlank()) {
                annotations.add(textNode.asText());
            }
        }

        return annotations;
    }
}
