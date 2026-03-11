package com.stablebridge.oncall.infrastructure.pagerduty;

import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.port.pagerduty.AlertNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.services.pagerduty.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PagerDutyNotifierAdapter implements AlertNotifier {

    private final WebClient pagerdutyWebClient;

    @Override
    public void addIncidentNote(String incidentId, String note) {
        log.info("Adding note to PagerDuty incident={}", incidentId);

        Map<String, Object> body = Map.of("note", Map.of("content", note));

        try {
            pagerdutyWebClient
                    .post()
                    .uri("/incidents/{incidentId}/notes", incidentId)
                    .bodyValue(body)
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
                    .bodyToMono(Void.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "PagerDuty incident not found: " + incidentId);
            }
            throw new ServiceUnavailableException(
                    "PagerDuty service error: HTTP " + e.getStatusCode().value());
        }

        log.info("Successfully added note to PagerDuty incident={}", incidentId);
    }
}
