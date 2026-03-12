package com.stablebridge.oncall.infrastructure.argocd;

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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class ArgoCDRollbackAdapterTest {

    private ArgoCDRollbackAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new ArgoCDRollbackAdapter(webClient);
    }

    @Test
    void shouldExecuteRollbackSuccessfully() {
        // given
        stubFor(
                post(urlPathEqualTo("/api/v1/applications/alert-api/rollback"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "metadata": {"name": "alert-api"},
                          "status": {
                            "sync": {"status": "Synced", "revision": "deploy-prev-789"},
                            "health": {"status": "Healthy"}
                          }
                        }
                        """)));

        // when
        var result = adapter.executeRollback("alert-api", "deploy-prev-789");

        // then
        assertThat(result.service()).isEqualTo("alert-api");
        assertThat(result.success()).isTrue();
        assertThat(result.newRevision()).isEqualTo("deploy-prev-789");
        assertThat(result.executedAt()).isNotNull();
        assertThat(result.message()).contains("deploy-prev-789");

        verify(
                postRequestedFor(urlPathEqualTo("/api/v1/applications/alert-api/rollback"))
                        .withRequestBody(matchingJsonPath("$.revision", equalTo("deploy-prev-789"))));
    }

    @Test
    void shouldThrowResourceNotFoundOn404() {
        // given
        stubFor(
                post(urlPathEqualTo("/api/v1/applications/not-found/rollback"))
                        .willReturn(aResponse().withStatus(404)));

        // then
        assertThatThrownBy(() -> adapter.executeRollback("not-found", "rev-123"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ArgoCD");
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                post(urlPathEqualTo("/api/v1/applications/alert-api/rollback"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(() -> adapter.executeRollback("alert-api", "rev-123"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("ArgoCD");
    }
}
