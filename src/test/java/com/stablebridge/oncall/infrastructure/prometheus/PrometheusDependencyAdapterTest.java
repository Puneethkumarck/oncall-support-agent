package com.stablebridge.oncall.infrastructure.prometheus;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.stablebridge.oncall.domain.model.common.HealthStatus;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.common.Trend;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class PrometheusDependencyAdapterTest {

    private PrometheusDependencyAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient = WebClient.builder()
                .baseUrl(wmInfo.getHttpBaseUrl())
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .build();
        adapter = new PrometheusDependencyAdapter(webClient);
    }

    @Test
    void shouldFetchDependencies() {
        // given — discovery query returns two dependencies
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .withQueryParam("query", com.github.tomakehurst.wiremock.client.WireMock.containing(
                        "http_client_requests_seconds_count"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "success",
                                  "data": {
                                    "resultType": "vector",
                                    "result": [
                                      {
                                        "metric": {"target_app": "evaluator"},
                                        "value": [1709900000, "150.5"]
                                      },
                                      {
                                        "metric": {"target_app": "notification-persister"},
                                        "value": [1709900000, "45.2"]
                                      }
                                    ]
                                  }
                                }
                                """)));

        // stub error rate queries — low error rate (GREEN)
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .withQueryParam("query", com.github.tomakehurst.wiremock.client.WireMock.containing(
                        "http_server_requests_seconds_count"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "success",
                                  "data": {
                                    "resultType": "vector",
                                    "result": [
                                      {
                                        "metric": {},
                                        "value": [1709900000, "0.002"]
                                      }
                                    ]
                                  }
                                }
                                """)));

        // stub latency queries
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .withQueryParam("query", com.github.tomakehurst.wiremock.client.WireMock.containing(
                        "histogram_quantile"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "status": "success",
                                  "data": {
                                    "resultType": "vector",
                                    "result": [
                                      {
                                        "metric": {},
                                        "value": [1709900000, "0.125"]
                                      }
                                    ]
                                  }
                                }
                                """)));

        // when
        var result = adapter.fetchDependencies("alert-api");

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("evaluator");
        assertThat(result.get(0).status()).isEqualTo(HealthStatus.GREEN);
        assertThat(result.get(0).trend()).isEqualTo(Trend.STABLE);
        assertThat(result.get(1).name()).isEqualTo("notification-persister");
    }

    @Test
    void shouldReturnEmptyListOn404() {
        // given
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(404)));

        // when
        var result = adapter.fetchDependencies("unknown-service");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(get(urlPathEqualTo("/api/v1/query"))
                .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(() -> adapter.fetchDependencies("alert-api"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Prometheus service error");
    }
}
