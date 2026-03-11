package com.stablebridge.oncall.infrastructure.loki;

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
class LokiAdapterTest {

    private LokiAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new LokiAdapter(webClient);
    }

    @Test
    void shouldSearchLogs() {
        // given
        stubFor(
                get(urlPathEqualTo("/loki/api/v1/query_range"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "status": "success",
                          "data": {
                            "resultType": "streams",
                            "result": [
                              {
                                "stream": {"app": "alert-api", "level": "error"},
                                "values": [
                                  ["1709900000000000000", "{\\"level\\":\\"ERROR\\",\\"message\\":\\"NullPointerException at PriceEvaluationService.evaluate\\",\\"logger\\":\\"com.example.Foo\\",\\"exception\\":\\"java.lang.NullPointerException\\\\n\\\\tat com.example.Foo.bar(Foo.java:42)\\"}"],
                                  ["1709900100000000000", "{\\"level\\":\\"ERROR\\",\\"message\\":\\"NullPointerException at PriceEvaluationService.evaluate\\",\\"logger\\":\\"com.example.Foo\\"}"]
                                ]
                              },
                              {
                                "stream": {"app": "alert-api", "level": "error"},
                                "values": [
                                  ["1709900200000000000", "{\\"level\\":\\"ERROR\\",\\"message\\":\\"Connection refused to Redis\\",\\"logger\\":\\"com.example.Cache\\",\\"exception\\":\\"java.io.IOException: Connection refused\\\\n\\\\tat com.example.Cache.connect(Cache.java:15)\\"}"]
                                ]
                              }
                            ]
                          }
                        }
                        """)));

        // when
        var result =
                adapter.searchLogs(
                        "alert-api",
                        Instant.parse("2024-03-08T10:00:00Z"),
                        Instant.parse("2024-03-08T11:00:00Z"),
                        "error");

        // then
        assertThat(result).hasSize(2);

        var npeCluster =
                result.stream()
                        .filter(c -> c.exceptionType().equals("NullPointerException"))
                        .findFirst()
                        .orElseThrow();
        assertThat(npeCluster.count()).isEqualTo(2);
        assertThat(npeCluster.sampleStackTrace()).contains("NullPointerException");
        assertThat(npeCluster.firstSeen()).isBefore(npeCluster.lastSeen());
        assertThat(npeCluster.isNew()).isFalse();
        assertThat(npeCluster.fingerprint()).isNotBlank();

        var ioCluster =
                result.stream()
                        .filter(c -> c.exceptionType().equals("IOException"))
                        .findFirst()
                        .orElseThrow();
        assertThat(ioCluster.count()).isEqualTo(1);
        assertThat(ioCluster.sampleStackTrace()).contains("Connection refused");
        assertThat(ioCluster.isNew()).isFalse();
    }

    @Test
    void shouldReturnEmptyListOn404() {
        // given
        stubFor(
                get(urlPathEqualTo("/loki/api/v1/query_range"))
                        .willReturn(aResponse().withStatus(404)));

        // when
        var result =
                adapter.searchLogs(
                        "alert-api",
                        Instant.parse("2024-03-08T10:00:00Z"),
                        Instant.parse("2024-03-08T11:00:00Z"),
                        null);

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                get(urlPathEqualTo("/loki/api/v1/query_range"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(
                        () ->
                                adapter.searchLogs(
                                        "alert-api",
                                        Instant.parse("2024-03-08T10:00:00Z"),
                                        Instant.parse("2024-03-08T11:00:00Z"),
                                        "error"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Loki");
    }
}
