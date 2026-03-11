package com.stablebridge.oncall.infrastructure.pagerduty;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class PagerDutyNotifierAdapterTest {

    private PagerDutyNotifierAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .defaultHeader("Content-Type", "application/json")
                        .build();
        adapter = new PagerDutyNotifierAdapter(webClient);
    }

    @Test
    void shouldAddIncidentNote() {
        // given
        stubFor(
                post(urlPathEqualTo("/incidents/INC-001/notes"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "note": {
                            "id": "NOTE-001",
                            "content": "Triage complete: deploy rollback recommended"
                          }
                        }
                        """)));

        // when
        adapter.addIncidentNote("INC-001", "Triage complete: deploy rollback recommended");

        // then
        verify(
                postRequestedFor(urlPathEqualTo("/incidents/INC-001/notes"))
                        .withRequestBody(
                                matchingJsonPath("$.note.content",
                                        equalTo("Triage complete: deploy rollback recommended"))));
    }

    @Test
    void shouldThrowResourceNotFoundOn404() {
        // given
        stubFor(
                post(urlPathEqualTo("/incidents/NOT-FOUND/notes"))
                        .willReturn(aResponse().withStatus(404)));

        // then
        assertThatThrownBy(
                        () -> adapter.addIncidentNote("NOT-FOUND", "some note"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("PagerDuty");
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                post(urlPathEqualTo("/incidents/INC-001/notes"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(
                        () -> adapter.addIncidentNote("INC-001", "some note"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("PagerDuty");
    }
}
