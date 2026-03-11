package com.stablebridge.oncall.infrastructure.prometheus;

import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.common.Trend;
import com.stablebridge.oncall.domain.model.health.DependencyStatus;
import com.stablebridge.oncall.domain.port.prometheus.DependencyGraphProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.services.prometheus.enabled",
        havingValue = "true",
        matchIfMissing = false)
class PrometheusDependencyAdapter implements DependencyGraphProvider {

    private final WebClient prometheusWebClient;

    @Override
    public List<DependencyStatus> fetchDependencies(String service) {
        log.info("Fetching dependency graph for service: {}", service);

        var discoveryQuery =
                "sum by (target_app) (rate(http_client_requests_seconds_count{app=\""
                        + service
                        + "\"}[5m]))";

        var discoveryResult = queryInstant(discoveryQuery);
        if (discoveryResult == null) {
            return Collections.emptyList();
        }

        var results = discoveryResult.path("data").path("result");
        List<DependencyStatus> dependencies = new ArrayList<>();

        for (var entry : results) {
            var depName = entry.path("metric").path("target_app").asText();
            var latency = fetchLatency(depName);
            var errorRate = fetchErrorRate(depName);
            var status = determineHealthStatus(errorRate);

            dependencies.add(new DependencyStatus(depName, status, latency, Trend.STABLE));
        }

        return dependencies;
    }

    private double fetchErrorRate(String dependency) {
        var query =
                "rate(http_server_requests_seconds_count{app=\""
                        + dependency
                        + "\",status=~\"5..\"}[5m])";
        var result = queryInstant(query);
        if (result == null) {
            return 0.0;
        }

        var results = result.path("data").path("result");
        if (results.isEmpty()) {
            return 0.0;
        }

        return Double.parseDouble(results.get(0).path("value").get(1).asText());
    }

    private double fetchLatency(String dependency) {
        var query =
                "histogram_quantile(0.99, rate(http_server_requests_seconds_bucket{app=\""
                        + dependency
                        + "\"}[5m]))";
        var result = queryInstant(query);
        if (result == null) {
            return 0.0;
        }

        var results = result.path("data").path("result");
        if (results.isEmpty()) {
            return 0.0;
        }

        return Double.parseDouble(results.get(0).path("value").get(1).asText());
    }

    private HealthStatus determineHealthStatus(double errorRate) {
        if (errorRate > 0.05) {
            return HealthStatus.RED;
        }
        if (errorRate > 0.01) {
            return HealthStatus.AMBER;
        }
        return HealthStatus.GREEN;
    }

    private JsonNode queryInstant(String promql) {
        var encodedQuery = URLEncoder.encode(promql, UTF_8);
        return prometheusWebClient
                .get()
                .uri("/api/v1/query?query=" + encodedQuery)
                .retrieve()
                .onStatus(
                        HttpStatusCode::is5xxServerError,
                        response -> {
                            throw new ServiceUnavailableException(
                                    "Prometheus service error: HTTP "
                                            + response.statusCode().value());
                        })
                .bodyToMono(JsonNode.class)
                .onErrorResume(
                        WebClientResponseException.class,
                        ex -> {
                            log.warn(
                                    "Prometheus returned {} for query: {}",
                                    ex.getStatusCode().value(),
                                    promql);
                            return Mono.empty();
                        })
                .block();
    }
}
