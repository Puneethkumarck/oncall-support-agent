package com.stablebridge.oncall.infrastructure.pagerduty;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.stablebridge.oncall.domain.model.common.AlertStatus;
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
class PagerDutyHistoryAdapterTest {

    private PagerDutyHistoryAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new PagerDutyHistoryAdapter(webClient);
    }

    @Test
    void shouldFetchAlertHistory() {
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
                              "description": "Timeout on alert-api",
                              "status": "resolved",
                              "created_at": "2026-03-09T14:00:00Z",
                              "last_status_change_at": "2026-03-09T14:30:00Z"
                            }
                          ]
                        }
                        """)));

        // when
        var result =
                adapter.fetchAlertHistory(
                        "alert-api-service-id",
                        Instant.parse("2026-03-08T00:00:00Z"),
                        Instant.parse("2026-03-11T00:00:00Z"));

        // then
        assertThat(result.service()).isEqualTo("alert-api-service-id");
        assertThat(result.totalAlerts()).isEqualTo(2);
        assertThat(result.recentAlerts()).hasSize(2);
        assertThat(result.isRecurring()).isTrue();
        assertThat(result.lastOccurrence()).isEqualTo(Instant.parse("2026-03-10T10:00:00Z"));

        var first = result.recentAlerts().get(0);
        assertThat(first.alertId()).isEqualTo("INC-001");
        assertThat(first.status()).isEqualTo(AlertStatus.RESOLVED);
        assertThat(first.ttResolve()).isNotNull();
    }

    @Test
    void shouldFetchIncidentTimeline() {
        // given
        stubFor(
                get(urlPathEqualTo("/incidents/INC-001/log_entries"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "log_entries": [
                            {
                              "created_at": "2026-03-10T10:00:00Z",
                              "type": "trigger_log_entry",
                              "agent": {
                                "summary": "PagerDuty"
                              }
                            },
                            {
                              "created_at": "2026-03-10T10:05:00Z",
                              "type": "acknowledge_log_entry",
                              "agent": {
                                "summary": "John Doe"
                              }
                            },
                            {
                              "created_at": "2026-03-10T10:15:00Z",
                              "type": "resolve_log_entry",
                              "agent": {
                                "summary": "John Doe"
                              }
                            }
                          ]
                        }
                        """)));

        // when
        var result = adapter.fetchIncidentTimeline("INC-001");

        // then
        assertThat(result).hasSize(3);

        var trigger = result.get(0);
        assertThat(trigger.timestamp()).isEqualTo(Instant.parse("2026-03-10T10:00:00Z"));
        assertThat(trigger.event()).isEqualTo("trigger_log_entry");
        assertThat(trigger.actor()).isEqualTo("PagerDuty");

        var ack = result.get(1);
        assertThat(ack.timestamp()).isEqualTo(Instant.parse("2026-03-10T10:05:00Z"));
        assertThat(ack.event()).isEqualTo("acknowledge_log_entry");
        assertThat(ack.actor()).isEqualTo("John Doe");

        var resolve = result.get(2);
        assertThat(resolve.timestamp()).isEqualTo(Instant.parse("2026-03-10T10:15:00Z"));
        assertThat(resolve.event()).isEqualTo("resolve_log_entry");
        assertThat(resolve.actor()).isEqualTo("John Doe");
    }

    @Test
    void shouldThrowResourceNotFoundOn404() {
        // given
        stubFor(
                get(urlPathEqualTo("/incidents/NOT-FOUND/log_entries"))
                        .willReturn(aResponse().withStatus(404)));

        // then
        assertThatThrownBy(() -> adapter.fetchIncidentTimeline("NOT-FOUND"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("PagerDuty");
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                get(urlPathEqualTo("/incidents/INC-001/log_entries"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(() -> adapter.fetchIncidentTimeline("INC-001"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("PagerDuty");
    }
}
