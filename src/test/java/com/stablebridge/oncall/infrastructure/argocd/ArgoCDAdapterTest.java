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
import java.time.Instant;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WireMockTest
class ArgoCDAdapterTest {

    private ArgoCDAdapter adapter;

    @BeforeEach
    void setUp(WireMockRuntimeInfo wmInfo) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        var webClient =
                WebClient.builder()
                        .baseUrl(wmInfo.getHttpBaseUrl())
                        .clientConnector(new JdkClientHttpConnector(httpClient))
                        .build();
        adapter = new ArgoCDAdapter(webClient);
    }

    @Test
    void shouldFetchLatestDeploy() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/v1/applications/alert-api"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "metadata": {"name": "alert-api"},
                          "status": {
                            "sync": {"status": "Synced", "revision": "abc123def456"},
                            "health": {"status": "Healthy"},
                            "operationState": {
                              "finishedAt": "2026-03-10T09:30:00Z",
                              "syncResult": {
                                "revision": "abc123def456",
                                "source": {"repoURL": "https://github.com/example/alert-api"}
                              }
                            },
                            "summary": {
                              "images": ["alert-api:v1.2.3"]
                            }
                          }
                        }
                        """)));

        // when
        var result = adapter.fetchLatestDeploy("alert-api");

        // then
        assertThat(result.appName()).isEqualTo("alert-api");
        assertThat(result.lastDeployId()).isEqualTo("abc123def456");
        assertThat(result.commitSha()).isEqualTo("abc123def456");
        assertThat(result.deployedAt()).isEqualTo(Instant.parse("2026-03-10T09:30:00Z"));
        assertThat(result.syncStatus()).isEqualTo("Synced");
        assertThat(result.health()).isEqualTo("Healthy");
        assertThat(result.timeSinceDeploy()).isNotNull();
        assertThat(result.changedImages()).containsExactly("alert-api:v1.2.3");
    }

    @Test
    void shouldFetchDeployDetail() {
        // given
        stubFor(
                get(urlPathEqualTo(
                                "/api/v1/applications/alert-api/revisions/abc123def456/metadata"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "author": "developer@example.com",
                          "date": "2026-03-10T09:30:00Z",
                          "message": "feat: add price threshold validation",
                          "signatureInfo": ""
                        }
                        """)));

        stubFor(
                get(urlPathEqualTo("/api/v1/applications/alert-api"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "metadata": {"name": "alert-api"},
                          "status": {
                            "sync": {"status": "Synced", "revision": "abc123def456"},
                            "health": {"status": "Healthy"},
                            "history": [
                              {"id": 1, "revision": "prev789", "deployedAt": "2026-03-09T15:00:00Z"},
                              {"id": 2, "revision": "abc123def456", "deployedAt": "2026-03-10T09:30:00Z"}
                            ]
                          }
                        }
                        """)));

        // when
        var result = adapter.fetchDeployDetail("alert-api", "abc123def456");

        // then
        assertThat(result.deployId()).isEqualTo("abc123def456");
        assertThat(result.commitSha()).isEqualTo("abc123def456");
        assertThat(result.author()).isEqualTo("developer@example.com");
        assertThat(result.commitMessage()).isEqualTo("feat: add price threshold validation");
        assertThat(result.deployedAt()).isEqualTo(Instant.parse("2026-03-10T09:30:00Z"));
        assertThat(result.previousRevision()).isEqualTo("prev789");
    }

    @Test
    void shouldFetchRollbackHistory() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/v1/applications/alert-api"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "metadata": {"name": "alert-api"},
                          "status": {
                            "sync": {"status": "Synced", "revision": "abc123"},
                            "health": {"status": "Healthy"},
                            "history": [
                              {"id": 1, "revision": "ghi789", "deployedAt": "2026-03-08T10:00:00Z"},
                              {"id": 2, "revision": "def456", "deployedAt": "2026-03-09T15:00:00Z"},
                              {"id": 3, "revision": "abc123", "deployedAt": "2026-03-10T09:30:00Z"}
                            ]
                          }
                        }
                        """)));

        // when
        var result = adapter.fetchRollbackHistory("alert-api");

        // then
        assertThat(result.currentRevision()).isEqualTo("abc123");
        assertThat(result.canRollback()).isTrue();
        assertThat(result.previousRevisions()).containsExactly("def456", "ghi789");
        assertThat(result.lastStableRevision()).isEqualTo("def456");
    }

    @Test
    void shouldFetchDeploysInWindow() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/v1/applications/alert-api"))
                        .willReturn(
                                aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                """
                        {
                          "metadata": {"name": "alert-api"},
                          "status": {
                            "sync": {"status": "Synced", "revision": "abc123"},
                            "health": {"status": "Healthy"},
                            "history": [
                              {"id": 1, "revision": "ghi789", "deployedAt": "2026-03-08T10:00:00Z"},
                              {"id": 2, "revision": "def456", "deployedAt": "2026-03-09T15:00:00Z"},
                              {"id": 3, "revision": "abc123", "deployedAt": "2026-03-10T09:30:00Z"}
                            ]
                          }
                        }
                        """)));

        // when
        var result =
                adapter.fetchDeploysInWindow(
                        "alert-api",
                        Instant.parse("2026-03-09T00:00:00Z"),
                        Instant.parse("2026-03-09T23:59:59Z"));

        // then
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().commitSha()).isEqualTo("def456");
        assertThat(result.getFirst().deployedAt())
                .isEqualTo(Instant.parse("2026-03-09T15:00:00Z"));
    }

    @Test
    void shouldThrowResourceNotFoundOn404() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/v1/applications/not-found"))
                        .willReturn(aResponse().withStatus(404)));

        // then
        assertThatThrownBy(() -> adapter.fetchLatestDeploy("not-found"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("ArgoCD");
    }

    @Test
    void shouldThrowServiceUnavailableOn500() {
        // given
        stubFor(
                get(urlPathEqualTo("/api/v1/applications/alert-api"))
                        .willReturn(aResponse().withStatus(500)));

        // then
        assertThatThrownBy(() -> adapter.fetchLatestDeploy("alert-api"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("ArgoCD");
    }
}
