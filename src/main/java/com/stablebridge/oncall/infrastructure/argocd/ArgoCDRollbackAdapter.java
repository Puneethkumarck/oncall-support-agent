package com.stablebridge.oncall.infrastructure.argocd;

import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.deploy.RollbackResult;
import com.stablebridge.oncall.domain.port.argocd.DeployRollbackProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.services.argocd.enabled",
        havingValue = "true",
        matchIfMissing = false)
class ArgoCDRollbackAdapter implements DeployRollbackProvider {

    private final WebClient argocdWebClient;

    @Override
    public RollbackResult executeRollback(String appName, String targetRevision) {
        log.info(
                "Executing rollback via ArgoCD for appName={} targetRevision={}",
                appName,
                targetRevision);

        try {
            JsonNode json =
                    argocdWebClient
                            .post()
                            .uri("/api/v1/applications/{appName}/rollback", appName)
                            .bodyValue(Map.of("id", 0, "revision", targetRevision))
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is4xxClientError,
                                    response -> {
                                        throw new ResourceNotFoundException(
                                                "ArgoCD application not found: " + appName);
                                    })
                            .onStatus(
                                    HttpStatusCode::is5xxServerError,
                                    response -> {
                                        throw new ServiceUnavailableException(
                                                "ArgoCD service error: HTTP "
                                                        + response.statusCode().value());
                                    })
                            .bodyToMono(JsonNode.class)
                            .block();

            String currentRevision =
                    json != null
                            ? json.path("status")
                                    .path("sync")
                                    .path("revision")
                                    .asText(targetRevision)
                            : targetRevision;

            log.info(
                    "Rollback completed for appName={} to revision={}",
                    appName,
                    targetRevision);

            return new RollbackResult(
                    appName,
                    true,
                    currentRevision,
                    targetRevision,
                    Instant.now(),
                    "Rollback to revision " + targetRevision + " completed successfully");
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "ArgoCD application not found: " + appName);
            }
            throw new ServiceUnavailableException(
                    "ArgoCD service error: HTTP " + e.getStatusCode().value());
        }
    }
}
