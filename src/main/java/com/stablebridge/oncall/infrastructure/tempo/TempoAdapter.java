package com.stablebridge.oncall.infrastructure.tempo;

import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.trace.CallChainStep;
import com.stablebridge.oncall.domain.port.tempo.TraceProvider;
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
        name = "app.services.tempo.enabled",
        havingValue = "true",
        matchIfMissing = false)
class TempoAdapter implements TraceProvider {

    private final WebClient tempoWebClient;

    @Override
    public List<CallChainStep> fetchTrace(String traceId) {
        log.info("Fetching trace from Tempo for traceId={}", traceId);

        JsonNode json;
        try {
            json =
                    tempoWebClient
                            .get()
                            .uri("/api/traces/{traceId}", traceId)
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is5xxServerError,
                                    response -> {
                                        throw new ServiceUnavailableException(
                                                "Tempo service error: HTTP "
                                                        + response.statusCode().value());
                                    })
                            .bodyToMono(JsonNode.class)
                            .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.warn(
                        "Tempo returned {} for traceId={} — returning empty list",
                        e.getStatusCode().value(),
                        traceId);
                return List.of();
            }
            throw new ServiceUnavailableException(
                    "Tempo service error: HTTP " + e.getStatusCode().value());
        }

        if (json == null) {
            return List.of();
        }

        return parseTraceResponse(json);
    }

    @Override
    public List<CallChainStep> searchTraces(
            String service, Instant from, Instant to, int limit) {
        log.info(
                "Searching Tempo traces for service={} from={} to={} limit={}",
                service,
                from,
                to,
                limit);

        JsonNode json;
        try {
            json =
                    tempoWebClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/api/search")
                                                    .queryParam("serviceName", "{serviceName}")
                                                    .queryParam("start", "{start}")
                                                    .queryParam("end", "{end}")
                                                    .queryParam("limit", "{limit}")
                                                    .build(
                                                            service,
                                                            String.valueOf(from.getEpochSecond()),
                                                            String.valueOf(to.getEpochSecond()),
                                                            String.valueOf(limit)))
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is5xxServerError,
                                    response -> {
                                        throw new ServiceUnavailableException(
                                                "Tempo service error: HTTP "
                                                        + response.statusCode().value());
                                    })
                            .bodyToMono(JsonNode.class)
                            .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                log.warn(
                        "Tempo returned {} for search — returning empty list",
                        e.getStatusCode().value());
                return List.of();
            }
            throw new ServiceUnavailableException(
                    "Tempo service error: HTTP " + e.getStatusCode().value());
        }

        if (json == null) {
            return List.of();
        }

        return parseSearchResponse(json);
    }

    private List<CallChainStep> parseTraceResponse(JsonNode json) {
        List<CallChainStep> steps = new ArrayList<>();

        JsonNode batches = json.get("batches");
        if (batches == null || !batches.isArray()) {
            return steps;
        }

        for (JsonNode batch : batches) {
            String serviceName = extractServiceName(batch);

            JsonNode scopeSpans = batch.get("scopeSpans");
            if (scopeSpans == null || !scopeSpans.isArray()) {
                continue;
            }

            for (JsonNode scopeSpan : scopeSpans) {
                JsonNode spans = scopeSpan.get("spans");
                if (spans == null || !spans.isArray()) {
                    continue;
                }

                for (JsonNode span : spans) {
                    String operation = span.has("operationName")
                            ? span.get("operationName").asText()
                            : "unknown";

                    long startNano = span.has("startTimeUnixNano")
                            ? Long.parseLong(span.get("startTimeUnixNano").asText())
                            : 0;
                    long endNano = span.has("endTimeUnixNano")
                            ? Long.parseLong(span.get("endTimeUnixNano").asText())
                            : 0;
                    long durationMs = (endNano - startNano) / 1_000_000;

                    String status = extractStatus(span);

                    steps.add(new CallChainStep(serviceName, operation, durationMs, status, false));
                }
            }
        }

        return steps;
    }

    private String extractServiceName(JsonNode batch) {
        JsonNode resource = batch.get("resource");
        if (resource == null) {
            return "unknown";
        }

        JsonNode attributes = resource.get("attributes");
        if (attributes == null || !attributes.isArray()) {
            return "unknown";
        }

        for (JsonNode attr : attributes) {
            if ("service.name".equals(attr.path("key").asText())) {
                return attr.path("value").path("stringValue").asText("unknown");
            }
        }

        return "unknown";
    }

    private String extractStatus(JsonNode span) {
        JsonNode statusNode = span.get("status");
        if (statusNode == null) {
            return "OK";
        }

        String code = statusNode.has("code") ? statusNode.get("code").asText() : "STATUS_CODE_OK";

        return switch (code) {
            case "STATUS_CODE_OK" -> "OK";
            case "STATUS_CODE_ERROR" -> "ERROR";
            case "STATUS_CODE_UNSET" -> "UNSET";
            default -> code;
        };
    }

    private List<CallChainStep> parseSearchResponse(JsonNode json) {
        List<CallChainStep> steps = new ArrayList<>();

        JsonNode traces = json.get("traces");
        if (traces == null || !traces.isArray()) {
            return steps;
        }

        for (JsonNode trace : traces) {
            String service = trace.has("rootServiceName")
                    ? trace.get("rootServiceName").asText()
                    : "unknown";
            String operation = trace.has("rootTraceName")
                    ? trace.get("rootTraceName").asText()
                    : "unknown";
            long durationMs = trace.has("durationMs")
                    ? trace.get("durationMs").asLong()
                    : 0;

            steps.add(new CallChainStep(service, operation, durationMs, "OK", false));
        }

        return steps;
    }
}
