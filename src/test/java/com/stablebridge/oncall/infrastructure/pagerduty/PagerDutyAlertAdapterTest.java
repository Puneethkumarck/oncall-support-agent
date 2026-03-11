package com.stablebridge.oncall.infrastructure.pagerduty;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.stablebridge.oncall.domain.model.common.AlertStatus;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
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
class PagerDutyAlertAdapterTest {

    private PagerDutyAlertAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new PagerDutyAlertAdapter(webClient);
    }

    @Test
    void shouldFetchAlert() {
        // given
        stubFor(
                get(urlPathEqualTo("/incidents/INC-001"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "incident": {
                            "id": "INC-001",
                            "service": {
                              "summary": "alert-api"
                            },
                            "urgency": "high",
                            "description": "High error rate on alert-api",
                            "created_at": "2026-03-10T10:00:00Z",
                            "html_url": "https://myorg.pagerduty.com/incidents/INC-001",
                            "incident_key": "alert-api-high-error-rate"
                          }
                        }
                        """)));

        // when
        var result = adapter.fetchAlert("INC-001");

        // then
        assertThat(result.alertId()).isEqualTo("INC-001");
        assertThat(result.service()).isEqualTo("alert-api");
        assertThat(result.severity()).isEqualTo(IncidentSeverity.SEV2);
        assertThat(result.description()).isEqualTo("High error rate on alert-api");
        assertThat(result.triggeredAt()).isEqualTo(Instant.parse("2026-03-10T10:00:00Z"));
        assertThat(result.runbookUrl())
                .isEqualTo("https://myorg.pagerduty.com/incidents/INC-001");
        assertThat(result.dedupKey()).isEqualTo("alert-api-high-error-rate");
    }

    @Test
    void shouldFetchAllAlerts() {
        // given
        stubFor(
                get(urlPathEqualTo("/incidents"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "incidents": [
                            {
                              "id": "INC-001",
                              "description": "High error rate on alert-api",
                              "status": "resolved",
                              "created_at": "2026-03-10T10:00:00Z",
                              "last_status_change_at": "2026-03-10T10:15:00Z"
                            },
                            {
                              "id": "INC-002",
                              "description": "CPU spike on evaluator",
                              "status": "triggered",
                              "created_at": "2026-03-10T11:00:00Z"
                            }
                          ]
                        }
                        """)));

        // when
        var result =
                adapter.fetchAllAlerts(
                        "platform",
                        Instant.parse("2026-03-10T00:00:00Z"),
                        Instant.parse("2026-03-10T23:59:59Z"));

        // then
        assertThat(result).hasSize(2);

        var first = result.get(0);
        assertThat(first.alertId()).isEqualTo("INC-001");
        assertThat(first.description()).isEqualTo("High error rate on alert-api");
        assertThat(first.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(first.triggeredAt()).isEqualTo(Instant.parse("2026-03-10T10:00:00Z"));
        assertThat(first.resolvedAt()).isEqualTo(Instant.parse("2026-03-10T10:15:00Z"));
        assertThat(first.ttResolve()).isNotNull();

        var second = result.get(1);
        assertThat(second.alertId()).isEqualTo("INC-002");
        assertThat(second.status()).isEqualTo(AlertStatus.TRIGGERED);
        assertThat(second.resolvedAt()).isNull();
        assertThat(second.ttResolve()).isNull();
    }

    @Test
    void shouldThrowResourceNotFoundOn404() {
        // given
        stubFor(
                get(urlPathEqualTo("/incidents/NOT-FOUND"))
                        .willReturn(aResponse().withStatus(404)));

        // then
        assertThatThrownBy(() -> adapter.fetchAlert("NOT-FOUND"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("PagerDuty");
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                get(urlPathEqualTo("/incidents/INC-001"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(() -> adapter.fetchAlert("INC-001"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("PagerDuty");
    }
}
