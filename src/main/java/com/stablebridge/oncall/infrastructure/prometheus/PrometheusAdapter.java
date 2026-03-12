package com.stablebridge.oncall.infrastructure.prometheus;

import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.model.metrics.MetricsWindow;
import com.stablebridge.oncall.domain.model.metrics.SLOSnapshot;
import com.stablebridge.oncall.domain.model.slo.BurnContributor;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
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
        name = "app.services.prometheus.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PrometheusAdapter implements MetricsProvider {

    private final WebClient prometheusWebClient;

    @Override
    public MetricsSnapshot fetchServiceMetrics(String service, Instant at) {
        log.info("Fetching Prometheus metrics for service={} at={}", service, at);

        double errorRate = queryInstant(
                "rate(http_server_requests_seconds_count{application=\""
                        + service
                        + "\",status=~\"5..\"}[5m])"
                        + " / rate(http_server_requests_seconds_count{application=\""
                        + service
                        + "\"}[5m])",
                at);

        double latencyP50 = queryInstant(
                "histogram_quantile(0.5, rate(http_server_requests_seconds_bucket{application=\""
                        + service
                        + "\"}[5m]))",
                at);

        double latencyP95 = queryInstant(
                "histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{application=\""
                        + service
                        + "\"}[5m]))",
                at);

        double latencyP99 = queryInstant(
                "histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{application=\""
                        + service
                        + "\"}[5m]))",
                at);

        double throughput = queryInstant(
                "sum(rate(http_server_requests_seconds_count{application=\"" + service + "\"}[5m]))", at);

        double cpuPercent =
                queryInstant("process_cpu_usage{application=\"" + service + "\"} * 100", at);

        double memoryPercent = queryInstant(
                "jvm_memory_used_bytes{application=\""
                        + service
                        + "\",area=\"heap\"}"
                        + " / jvm_memory_max_bytes{application=\""
                        + service
                        + "\",area=\"heap\"} * 100",
                at);

        return new MetricsSnapshot(
                service,
                errorRate,
                latencyP50,
                latencyP95,
                latencyP99,
                throughput,
                cpuPercent,
                memoryPercent,
                0.0,
                at);
    }

    @Override
    public MetricsWindow fetchMetricsWindow(String service, Instant from, Instant to) {
        log.info("Fetching Prometheus metrics window for service={} from={} to={}", service, from, to);

        Instant midpoint = Instant.ofEpochSecond((from.getEpochSecond() + to.getEpochSecond()) / 2);
        MetricsSnapshot snapshot = fetchServiceMetrics(service, midpoint);
        return new MetricsWindow(from, to, snapshot);
    }

    @Override
    public SLOSnapshot fetchSLOBudget(String service, String sloName) {
        log.info("Fetching SLO budget for service={} sloName={}", service, sloName);

        Instant now = Instant.now();

        double budgetRemaining = queryInstant(
                "1 - (sum(rate(http_server_requests_seconds_count{application=\""
                        + service
                        + "\",status=~\"5..\"}[30d]))"
                        + " / sum(rate(http_server_requests_seconds_count{application=\""
                        + service
                        + "\"}[30d])))",
                now);

        double burnRate = queryInstant(
                "sum(rate(http_server_requests_seconds_count{application=\""
                        + service
                        + "\",status=~\"5..\"}[1h]))"
                        + " / (1 - 0.999)",
                now);

        double budgetTotal = 1.0;
        double budgetConsumed = budgetTotal - budgetRemaining;

        Instant projectedBreach = null;
        if (burnRate > 0 && budgetRemaining > 0) {
            long secondsUntilBreach = (long) (budgetRemaining / burnRate * 3600);
            projectedBreach = now.plusSeconds(secondsUntilBreach);
        }

        return new SLOSnapshot(
                sloName, budgetTotal, budgetConsumed, budgetRemaining, burnRate, projectedBreach);
    }

    @Override
    public List<BurnContributor> fetchBurnContributors(String service, Instant from, Instant to) {
        log.info(
                "Fetching burn contributors for service={} from={} to={}",
                service,
                from,
                to);

        Instant midpoint = Instant.ofEpochSecond((from.getEpochSecond() + to.getEpochSecond()) / 2);

        String promql = "topk(5, sum by (uri) (rate(http_server_requests_seconds_count{application=\""
                + service
                + "\",status=~\"5..\"}[5m])))";

        JsonNode json = queryRaw(promql, midpoint);
        return parseBurnContributors(json);
    }

    @Override
    public List<SLOSnapshot> fetchBurnHistory(String service, Instant from, Instant to) {
        log.info(
                "Fetching burn history for service={} from={} to={}", service, from, to);

        String promql = "sum(rate(http_server_requests_seconds_count{application=\""
                + service
                + "\",status=~\"5..\"}[1h])) / (1 - 0.999)";

        JsonNode json = queryRange(promql, from, to, "300");
        return parseBurnHistory(service, json);
    }

    private double queryInstant(String promql, Instant at) {
        JsonNode json = queryRaw(promql, at);
        return extractScalarValue(json);
    }

    private JsonNode queryRaw(String promql, Instant at) {
        try {
            return prometheusWebClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/query")
                            .queryParam("query", "{query}")
                            .queryParam("time", "{time}")
                            .build(promql, at.getEpochSecond()))
                    .retrieve()
                    .onStatus(
                            HttpStatusCode::is4xxClientError,
                            response -> {
                                throw new ResourceNotFoundException(
                                        "Prometheus metrics not found for query: " + promql);
                            })
                    .onStatus(
                            HttpStatusCode::is5xxServerError,
                            response -> {
                                throw new ServiceUnavailableException(
                                        "Prometheus service error: HTTP "
                                                + response.statusCode().value());
                            })
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "Prometheus metrics not found for query: " + promql);
            }
            throw new ServiceUnavailableException(
                    "Prometheus service error: HTTP " + e.getStatusCode().value());
        }
    }

    private JsonNode queryRange(String promql, Instant from, Instant to, String step) {
        try {
            return prometheusWebClient
                    .get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/v1/query_range")
                            .queryParam("query", "{query}")
                            .queryParam("start", "{start}")
                            .queryParam("end", "{end}")
                            .queryParam("step", "{step}")
                            .build(promql, from.getEpochSecond(), to.getEpochSecond(), step))
                    .retrieve()
                    .onStatus(
                            HttpStatusCode::is4xxClientError,
                            response -> {
                                throw new ResourceNotFoundException(
                                        "Prometheus metrics not found for service");
                            })
                    .onStatus(
                            HttpStatusCode::is5xxServerError,
                            response -> {
                                throw new ServiceUnavailableException(
                                        "Prometheus service error: HTTP "
                                                + response.statusCode().value());
                            })
                    .bodyToMono(JsonNode.class)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "Prometheus metrics not found for service");
            }
            throw new ServiceUnavailableException(
                    "Prometheus service error: HTTP " + e.getStatusCode().value());
        }
    }

    private double extractScalarValue(JsonNode json) {
        if (json == null) {
            return 0.0;
        }

        JsonNode data = json.get("data");
        if (data == null) {
            return 0.0;
        }

        JsonNode result = data.get("result");
        if (result == null || !result.isArray() || result.isEmpty()) {
            return 0.0;
        }

        JsonNode firstResult = result.get(0);
        JsonNode value = firstResult.get("value");
        if (value == null || !value.isArray() || value.size() < 2) {
            return 0.0;
        }

        try {
            return Double.parseDouble(value.get(1).asText());
        } catch (NumberFormatException e) {
            log.warn("Failed to parse Prometheus value: {}", value.get(1).asText());
            return 0.0;
        }
    }

    private List<BurnContributor> parseBurnContributors(JsonNode json) {
        List<BurnContributor> contributors = new ArrayList<>();

        if (json == null) {
            return contributors;
        }

        JsonNode data = json.get("data");
        if (data == null) {
            return contributors;
        }

        JsonNode result = data.get("result");
        if (result == null || !result.isArray()) {
            return contributors;
        }

        double total = 0.0;
        List<RawContributor> rawContributors = new ArrayList<>();
        for (JsonNode item : result) {
            JsonNode metric = item.get("metric");
            JsonNode value = item.get("value");
            if (metric == null || value == null || !value.isArray() || value.size() < 2) {
                continue;
            }

            String endpoint = metric.has("uri") ? metric.get("uri").asText() : "unknown";
            double rate;
            try {
                rate = Double.parseDouble(value.get(1).asText());
            } catch (NumberFormatException e) {
                continue;
            }
            total += rate;
            rawContributors.add(new RawContributor(endpoint, rate));
        }

        for (RawContributor raw : rawContributors) {
            double contributionPercent = total > 0 ? (raw.rate() / total) * 100.0 : 0.0;
            contributors.add(new BurnContributor(raw.endpoint(), "5xx", contributionPercent));
        }

        return contributors;
    }

    private List<SLOSnapshot> parseBurnHistory(String service, JsonNode json) {
        List<SLOSnapshot> snapshots = new ArrayList<>();

        if (json == null) {
            return snapshots;
        }

        JsonNode data = json.get("data");
        if (data == null) {
            return snapshots;
        }

        JsonNode result = data.get("result");
        if (result == null || !result.isArray() || result.isEmpty()) {
            return snapshots;
        }

        JsonNode firstResult = result.get(0);
        JsonNode values = firstResult.get("values");
        if (values == null || !values.isArray()) {
            return snapshots;
        }

        for (JsonNode datapoint : values) {
            if (!datapoint.isArray() || datapoint.size() < 2) {
                continue;
            }

            long epochSeconds = datapoint.get(0).asLong();
            double burnRate;
            try {
                burnRate = Double.parseDouble(datapoint.get(1).asText());
            } catch (NumberFormatException e) {
                continue;
            }

            Instant timestamp = Instant.ofEpochSecond(epochSeconds);
            snapshots.add(new SLOSnapshot(
                    service + "-burn",
                    1.0,
                    0.0,
                    0.0,
                    burnRate,
                    null));
        }

        return snapshots;
    }

    private record RawContributor(String endpoint, double rate) {}
}
