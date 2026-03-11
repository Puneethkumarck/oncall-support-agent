package com.stablebridge.oncall.infrastructure.prometheus;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

@WireMockTest
class PrometheusAdapterTest {

    private PrometheusAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient = WebClient.builder()
                .baseUrl(wmInfo.getHttpBaseUrl())
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .build();
        adapter = new PrometheusAdapter(webClient);
    }

    @Test
    void shouldFetchServiceMetrics() {
        // given
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(prometheusSuccessResponse("0.05"))));

        Instant at = Instant.parse("2026-03-10T10:00:00Z");

        // when
        var result = adapter.fetchServiceMetrics("alert-api", at);

        // then
        assertThat(result.service()).isEqualTo("alert-api");
        assertThat(result.errorRate()).isCloseTo(0.05, within(0.001));
        assertThat(result.latencyP50()).isCloseTo(0.05, within(0.001));
        assertThat(result.latencyP95()).isCloseTo(0.05, within(0.001));
        assertThat(result.latencyP99()).isCloseTo(0.05, within(0.001));
        assertThat(result.throughput()).isCloseTo(0.05, within(0.001));
        assertThat(result.cpuPercent()).isCloseTo(0.05, within(0.001));
        assertThat(result.memoryPercent()).isCloseTo(0.05, within(0.001));
        assertThat(result.collectedAt()).isEqualTo(at);
    }

    @Test
    void shouldFetchMetricsWindow() {
        // given
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(prometheusSuccessResponse("1500.0"))));

        Instant from = Instant.parse("2026-03-10T09:30:00Z");
        Instant to = Instant.parse("2026-03-10T10:00:00Z");

        // when
        var result = adapter.fetchMetricsWindow("alert-api", from, to);

        // then
        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);
        assertThat(result.snapshot()).isNotNull();
        assertThat(result.snapshot().service()).isEqualTo("alert-api");
        assertThat(result.snapshot().throughput()).isCloseTo(1500.0, within(0.001));
    }

    @Test
    void shouldFetchSLOBudget() {
        // given
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(prometheusSuccessResponse("0.95"))));

        // when
        var result = adapter.fetchSLOBudget("alert-api", "availability-99.9");

        // then
        assertThat(result.sloName()).isEqualTo("availability-99.9");
        assertThat(result.budgetTotal()).isEqualTo(1.0);
        assertThat(result.budgetRemaining()).isCloseTo(0.95, within(0.001));
        assertThat(result.burnRate()).isCloseTo(0.95, within(0.001));
    }

    @Test
    void shouldFetchBurnContributors() {
        // given
        String responseBody =
                """
                {
                  "status": "success",
                  "data": {
                    "resultType": "vector",
                    "result": [
                      {
                        "metric": {"uri": "/api/v1/alerts"},
                        "value": [1709900000, "0.8"]
                      },
                      {
                        "metric": {"uri": "/api/v1/health"},
                        "value": [1709900000, "0.2"]
                      }
                    ]
                  }
                }
                """;
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        Instant from = Instant.parse("2026-03-10T09:00:00Z");
        Instant to = Instant.parse("2026-03-10T10:00:00Z");

        // when
        var result = adapter.fetchBurnContributors("alert-api", from, to);

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).endpoint()).isEqualTo("/api/v1/alerts");
        assertThat(result.get(0).contributionPercent()).isCloseTo(80.0, within(0.1));
        assertThat(result.get(1).endpoint()).isEqualTo("/api/v1/health");
        assertThat(result.get(1).contributionPercent()).isCloseTo(20.0, within(0.1));
    }

    @Test
    void shouldFetchBurnHistory() {
        // given
        String responseBody =
                """
                {
                  "status": "success",
                  "data": {
                    "resultType": "matrix",
                    "result": [
                      {
                        "metric": {},
                        "values": [
                          [1709900000, "1.5"],
                          [1709900300, "2.0"],
                          [1709900600, "1.8"]
                        ]
                      }
                    ]
                  }
                }
                """;
        stubFor(get(urlPathEqualTo("/api/v1/query_range"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));

        Instant from = Instant.parse("2026-03-10T09:00:00Z");
        Instant to = Instant.parse("2026-03-10T10:00:00Z");

        // when
        var result = adapter.fetchBurnHistory("alert-api", from, to);

        // then
        assertThat(result).hasSize(3);
        assertThat(result.get(0).burnRate()).isCloseTo(1.5, within(0.001));
        assertThat(result.get(1).burnRate()).isCloseTo(2.0, within(0.001));
        assertThat(result.get(2).burnRate()).isCloseTo(1.8, within(0.001));
    }

    @Test
    void shouldThrowResourceNotFoundOn404() {
        // given
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(404)));

        Instant at = Instant.parse("2026-03-10T10:00:00Z");

        // then
        assertThatThrownBy(() -> adapter.fetchServiceMetrics("missing-service", at))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Prometheus");
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(500)));

        Instant at = Instant.parse("2026-03-10T10:00:00Z");

        // then
        assertThatThrownBy(() -> adapter.fetchServiceMetrics("alert-api", at))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Prometheus");
    }

    @Test
    void shouldReturnZeroWhenPrometheusReturnsEmptyResult() {
        // given
        String emptyResponse =
                """
                {
                  "status": "success",
                  "data": {
                    "resultType": "vector",
                    "result": []
                  }
                }
                """;
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(emptyResponse)));

        Instant at = Instant.parse("2026-03-10T10:00:00Z");

        // when
        var result = adapter.fetchServiceMetrics("alert-api", at);

        // then
        assertThat(result.errorRate()).isEqualTo(0.0);
        assertThat(result.latencyP50()).isEqualTo(0.0);
        assertThat(result.throughput()).isEqualTo(0.0);
    }

    private String prometheusSuccessResponse(String value) {
        return """
                {
                  "status": "success",
                  "data": {
                    "resultType": "vector",
                    "result": [
                      {
                        "metric": {},
                        "value": [1709900000, "%s"]
                      }
                    ]
                  }
                }
                """
                .formatted(value);
    }
}
