package com.stablebridge.oncall.infrastructure.notification;

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
class SlackAdapterTest {

    private SlackAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new SlackAdapter(webClient);
    }

    @Test
    void shouldSendMessage() {
        // given
        stubFor(
                post(urlEqualTo("/"))
                        .willReturn(
                                aResponse()
                                        .withStatus(200)
                                        .withBody("ok")));

        // when
        adapter.sendMessage("#incidents", "Test alert message");

        // then
        verify(
                postRequestedFor(urlEqualTo("/"))
                        .withRequestBody(containing("#incidents")));
    }

    @Test
    void shouldThrowResourceNotFoundOn404() {
        // given
        stubFor(
                post(urlEqualTo("/"))
                        .willReturn(aResponse().withStatus(404)));

        // then
        assertThatThrownBy(() -> adapter.sendMessage("#incidents", "Test message"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Slack");
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                post(urlEqualTo("/"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(() -> adapter.sendMessage("#incidents", "Test message"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Slack");
    }
}
