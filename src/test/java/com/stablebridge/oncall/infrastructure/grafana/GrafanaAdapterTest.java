package com.stablebridge.oncall.infrastructure.grafana;

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
class GrafanaAdapterTest {

    private GrafanaAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new GrafanaAdapter(webClient);
    }

    @Test
    void shouldFetchAnnotations() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/annotations"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        [
                          {
                            "id": 1,
                            "dashboardUID": "dashboard-001",
                            "text": "Deploy abc123 by user@example.com",
                            "time": 1709900000000,
                            "tags": ["deploy", "evaluator"]
                          },
                          {
                            "id": 2,
                            "dashboardUID": "dashboard-001",
                            "text": "SLO budget warning — 20% remaining",
                            "time": 1709903600000,
                            "tags": ["slo", "alert"]
                          }
                        ]
                        """)));

        // when
        var result =
                adapter.fetchAnnotations(
                        "dashboard-001",
                        Instant.parse("2024-03-08T10:00:00Z"),
                        Instant.parse("2024-03-08T11:00:00Z"));

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isEqualTo("Deploy abc123 by user@example.com");
        assertThat(result.get(1)).isEqualTo("SLO budget warning — 20% remaining");
    }

    @Test
    void shouldReturnEmptyListOn404() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/annotations"))
                        .willReturn(aResponse().withStatus(404)));

        // when
        var result =
                adapter.fetchAnnotations(
                        "dashboard-001",
                        Instant.parse("2024-03-08T10:00:00Z"),
                        Instant.parse("2024-03-08T11:00:00Z"));

        // then
        assertThat(result).isEmpty();
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/annotations"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(
                        () ->
                                adapter.fetchAnnotations(
                                        "dashboard-001",
                                        Instant.parse("2024-03-08T10:00:00Z"),
                                        Instant.parse("2024-03-08T11:00:00Z")))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Grafana");
    }
}
