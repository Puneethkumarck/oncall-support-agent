package com.stablebridge.oncall.infrastructure.loki;

import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.services.loki.enabled",
        havingValue = "true",
        matchIfMissing = false)
class LokiAdapter implements LogSearchProvider {

    private final WebClient lokiWebClient;

    @Override
    public List<LogCluster> searchLogs(
            String service, Instant from, Instant to, String severityFilter) {
        log.info(
                "Searching Loki logs for service={} from={} to={} severity={}",
                service,
                from,
                to,
                severityFilter);

        String logqlQuery = buildLogqlQuery(service, severityFilter);

        JsonNode json;
        try {
            json =
                    lokiWebClient
                            .get()
                            .uri(
                                    uriBuilder ->
                                            uriBuilder
                                                    .path("/loki/api/v1/query_range")
                                                    .queryParam("query", "{query}")
                                                    .queryParam("start", "{start}")
                                                    .queryParam("end", "{end}")
                                                    .queryParam("limit", 1000)
                                                    .build(
                                                            logqlQuery,
                                                            String.valueOf(from.getEpochSecond()),
                                                            String.valueOf(to.getEpochSecond())))
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is5xxServerError,
                                    response -> {
                                        throw new ServiceUnavailableException(
                                                "Loki service error: HTTP "
                                                        + response.statusCode().value());
                                    })
                            .bodyToMono(JsonNode.class)
                            .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError() || e.getStatusCode().is2xxSuccessful()) {
                log.warn(
                        "Loki returned {} — returning empty list",
                        e.getStatusCode().value());
                return List.of();
            }
            throw new ServiceUnavailableException(
                    "Loki service error: HTTP " + e.getStatusCode().value());
        }

        if (json == null) {
            return List.of();
        }

        return parseResponse(json);
    }

    private String buildLogqlQuery(String service, String severityFilter) {
        StringBuilder query = new StringBuilder();
        query.append("{container=~\".*").append(service).append(".*\"}");
        if (severityFilter != null && !severityFilter.isBlank()) {
            query.append(" |~ \"(?i)").append(severityFilter).append("\"");
        }
        return query.toString();
    }

    private List<LogCluster> parseResponse(JsonNode json) {
        JsonNode data = json.get("data");
        if (data == null) {
            return List.of();
        }

        JsonNode result = data.get("result");
        if (result == null || !result.isArray() || result.isEmpty()) {
            return List.of();
        }

        Map<String, List<ParsedLogEntry>> clusterMap = new LinkedHashMap<>();

        for (JsonNode stream : result) {
            JsonNode values = stream.get("values");
            if (values == null || !values.isArray()) {
                continue;
            }

            for (JsonNode value : values) {
                if (!value.isArray() || value.size() < 2) {
                    continue;
                }

                long nanos = Long.parseLong(value.get(0).asText());
                Instant timestamp = Instant.ofEpochSecond(nanos / 1_000_000_000L);
                String logLine = value.get(1).asText();

                ParsedLogEntry entry = parseLogEntry(timestamp, logLine);
                String exceptionType = entry.exceptionType();

                clusterMap.computeIfAbsent(exceptionType, k -> new ArrayList<>()).add(entry);
            }
        }

        List<LogCluster> clusters = new ArrayList<>();
        for (Map.Entry<String, List<ParsedLogEntry>> entry : clusterMap.entrySet()) {
            String exceptionType = entry.getKey();
            List<ParsedLogEntry> entries = entry.getValue();

            Instant firstSeen = entries.stream()
                    .map(ParsedLogEntry::timestamp)
                    .min(Instant::compareTo)
                    .orElse(Instant.EPOCH);
            Instant lastSeen = entries.stream()
                    .map(ParsedLogEntry::timestamp)
                    .max(Instant::compareTo)
                    .orElse(Instant.EPOCH);

            String sampleStackTrace = entries.stream()
                    .map(ParsedLogEntry::stackTrace)
                    .filter(st -> st != null && !st.isBlank())
                    .findFirst()
                    .orElse(null);

            String fingerprint = exceptionType + "-" + Math.abs(exceptionType.hashCode());

            clusters.add(new LogCluster(
                    exceptionType,
                    fingerprint,
                    entries.size(),
                    firstSeen,
                    lastSeen,
                    sampleStackTrace,
                    false));
        }

        return clusters;
    }

    private ParsedLogEntry parseLogEntry(Instant timestamp, String logLine) {
        String exceptionType = "Unknown";
        String stackTrace = null;

        try {
            var objectMapper = new tools.jackson.databind.ObjectMapper();
            JsonNode logJson = objectMapper.readTree(logLine);

            if (logJson.has("exception") && !logJson.get("exception").asText().isBlank()) {
                String exception = logJson.get("exception").asText();
                exceptionType = extractExceptionType(exception);
                stackTrace = exception;
            } else if (logJson.has("message")) {
                String message = logJson.get("message").asText();
                exceptionType = extractExceptionTypeFromMessage(message);
            }
        } catch (Exception e) {
            log.debug("Failed to parse log line as JSON: {}", logLine);
            exceptionType = extractExceptionTypeFromMessage(logLine);
        }

        return new ParsedLogEntry(timestamp, exceptionType, stackTrace);
    }

    private String extractExceptionType(String exceptionField) {
        String firstLine = exceptionField.split("\\n")[0].trim();
        int colonIndex = firstLine.indexOf(':');
        if (colonIndex > 0) {
            String fullClassName = firstLine.substring(0, colonIndex).trim();
            int lastDot = fullClassName.lastIndexOf('.');
            return lastDot > 0 ? fullClassName.substring(lastDot + 1) : fullClassName;
        }
        int lastDot = firstLine.lastIndexOf('.');
        return lastDot > 0 ? firstLine.substring(lastDot + 1) : firstLine;
    }

    private String extractExceptionTypeFromMessage(String message) {
        String[] knownExceptions = {
            "NullPointerException",
            "IllegalArgumentException",
            "RuntimeException",
            "IOException",
            "TimeoutException",
            "ConnectionException",
            "OutOfMemoryError"
        };
        for (String exception : knownExceptions) {
            if (message.contains(exception)) {
                return exception;
            }
        }
        return "Unknown";
    }

    private record ParsedLogEntry(Instant timestamp, String exceptionType, String stackTrace) {}
}
