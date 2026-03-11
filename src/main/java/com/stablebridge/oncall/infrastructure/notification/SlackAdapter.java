package com.stablebridge.oncall.infrastructure.notification;

import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.port.notification.SlackNotifier;
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
        name = "app.services.slack.enabled",
        havingValue = "true",
        matchIfMissing = false)
class SlackAdapter implements SlackNotifier {

    private final WebClient slackWebClient;

    @Override
    public void sendMessage(String channel, String message) {
        log.info("Sending Slack message to channel={}", channel);

        try {
            slackWebClient
                    .post()
                    .uri("/")
                    .bodyValue(Map.of("channel", channel, "text", message))
                    .retrieve()
                    .onStatus(
                            HttpStatusCode::is4xxClientError,
                            response -> {
                                throw new ResourceNotFoundException(
                                        "Slack channel not found: HTTP "
                                                + response.statusCode().value());
                            })
                    .onStatus(
                            HttpStatusCode::is5xxServerError,
                            response -> {
                                throw new ServiceUnavailableException(
                                        "Slack service error: HTTP "
                                                + response.statusCode().value());
                            })
                    .bodyToMono(String.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "Slack channel not found: HTTP " + e.getStatusCode().value());
            }
            throw new ServiceUnavailableException(
                    "Slack service error: HTTP " + e.getStatusCode().value());
        }
    }
}
