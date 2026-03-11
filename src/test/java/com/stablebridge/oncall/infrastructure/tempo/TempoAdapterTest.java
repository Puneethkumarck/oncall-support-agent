package com.stablebridge.oncall.infrastructure.tempo;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class TempoAdapterTest {

    private TempoAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new TempoAdapter(webClient);
    }

    @Test
    void shouldFetchTrace() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/traces/abc123"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "batches": [
                            {
                              "resource": {
                                "attributes": [
                                  {"key": "service.name", "value": {"stringValue": "alert-api"}}
                                ]
                              },
                              "scopeSpans": [
                                {
                                  "spans": [
                                    {
                                      "traceId": "abc123",
                                      "spanId": "span-1",
                                      "operationName": "POST /api/v1/alerts",
                                      "startTimeUnixNano": "1709900000000000000",
                                      "endTimeUnixNano": "1709900000150000000",
                                      "status": {"code": "STATUS_CODE_OK"}
                                    },
                                    {
                                      "traceId": "abc123",
                                      "spanId": "span-2",
                                      "operationName": "SELECT alerts",
                                      "startTimeUnixNano": "1709900000010000000",
                                      "endTimeUnixNano": "1709900000060000000",
                                      "status": {"code": "STATUS_CODE_OK"}
                                    }
                                  ]
                                }
                              ]
                            },
                            {
                              "resource": {
                                "attributes": [
                                  {"key": "service.name", "value": {"stringValue": "evaluator"}}
                                ]
                              },
                              "scopeSpans": [
                                {
                                  "spans": [
                                    {
                                      "traceId": "abc123",
                                      "spanId": "span-3",
                                      "operationName": "evaluate",
                                      "startTimeUnixNano": "1709900000000000000",
                                      "endTimeUnixNano": "1709900002500000000",
                                      "status": {"code": "STATUS_CODE_ERROR"}
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                        """)));

        // when
        var result = adapter.fetchTrace("abc123");

        // then
        assertThat(result).hasSize(3);

        var alertApiSteps = result.stream()
                .filter(s -> s.service().equals("alert-api"))
                .toList();
        assertThat(alertApiSteps).hasSize(2);

        var postStep = alertApiSteps.stream()
                .filter(s -> s.operation().equals("POST /api/v1/alerts"))
                .findFirst()
                .orElseThrow();
        assertThat(postStep.durationMs()).isEqualTo(150);
        assertThat(postStep.status()).isEqualTo("OK");
        assertThat(postStep.isBottleneck()).isFalse();

        var selectStep = alertApiSteps.stream()
                .filter(s -> s.operation().equals("SELECT alerts"))
                .findFirst()
                .orElseThrow();
        assertThat(selectStep.durationMs()).isEqualTo(50);
        assertThat(selectStep.status()).isEqualTo("OK");

        var evaluatorStep = result.stream()
                .filter(s -> s.service().equals("evaluator"))
                .findFirst()
                .orElseThrow();
        assertThat(evaluatorStep.operation()).isEqualTo("evaluate");
        assertThat(evaluatorStep.durationMs()).isEqualTo(2500);
        assertThat(evaluatorStep.status()).isEqualTo("ERROR");
        assertThat(evaluatorStep.isBottleneck()).isFalse();
    }

    @Test
    void shouldSearchTraces() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/search"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "traces": [
                            {
                              "traceID": "abc123",
                              "rootServiceName": "alert-api",
                              "rootTraceName": "POST /api/v1/alerts",
                              "startTimeUnixNano": "1709900000000000000",
                              "durationMs": 150
                            },
                            {
                              "traceID": "def456",
                              "rootServiceName": "evaluator",
                              "rootTraceName": "evaluate",
                              "startTimeUnixNano": "1709900100000000000",
                              "durationMs": 2500
                            }
                          ]
                        }
                        """)));

        // when
        var result =
                adapter.searchTraces(
                        "alert-api",
                        Instant.parse("2024-03-08T10:00:00Z"),
                        Instant.parse("2024-03-08T11:00:00Z"),
                        10);

        // then
        assertThat(result).hasSize(2);

        var firstTrace = result.get(0);
        assertThat(firstTrace.service()).isEqualTo("alert-api");
        assertThat(firstTrace.operation()).isEqualTo("POST /api/v1/alerts");
        assertThat(firstTrace.durationMs()).isEqualTo(150);
        assertThat(firstTrace.status()).isEqualTo("OK");
        assertThat(firstTrace.isBottleneck()).isFalse();

        var secondTrace = result.get(1);
        assertThat(secondTrace.service()).isEqualTo("evaluator");
        assertThat(secondTrace.operation()).isEqualTo("evaluate");
        assertThat(secondTrace.durationMs()).isEqualTo(2500);
    }

    @Test
    void shouldReturnEmptyListOn404() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/traces/not-found"))
                        .willReturn(aResponse().withStatus(404)));

        // when
        var result = adapter.fetchTrace("not-found");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/traces/abc123"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(() -> adapter.fetchTrace("abc123"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Tempo");
    }
}
