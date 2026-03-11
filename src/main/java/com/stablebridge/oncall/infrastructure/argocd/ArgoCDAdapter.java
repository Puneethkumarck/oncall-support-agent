package com.stablebridge.oncall.infrastructure.argocd;

import com.stablebridge.oncall.domain.model.common.ResourceNotFoundException;
import com.stablebridge.oncall.domain.model.common.ServiceUnavailableException;
import com.stablebridge.oncall.domain.model.deploy.DeployDetail;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.deploy.RollbackHistory;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.services.argocd.enabled",
        havingValue = "true",
        matchIfMissing = false)
class ArgoCDAdapter implements DeployHistoryProvider {

    private final WebClient argocdWebClient;

    @Override
    public DeploySnapshot fetchLatestDeploy(String appName) {
        log.info("Fetching latest deploy from ArgoCD for appName={}", appName);

        JsonNode json = fetchApplication(appName);
        return parseDeploySnapshot(json);
    }

    @Override
    public DeployDetail fetchDeployDetail(String appName, String revision) {
        log.info(
                "Fetching deploy detail from ArgoCD for appName={} revision={}",
                appName,
                revision);

        JsonNode metadata = fetchRevisionMetadata(appName, revision);
        JsonNode appJson = fetchApplication(appName);

        String author = metadata.has("author") ? metadata.get("author").asText() : "unknown";
        String message = metadata.has("message") ? metadata.get("message").asText() : "";
        Instant deployedAt = metadata.has("date")
                ? Instant.parse(metadata.get("date").asText())
                : Instant.now();

        String previousRevision = findPreviousRevision(appJson, revision);

        return new DeployDetail(
                revision, revision, author, message, "", List.of(), deployedAt, previousRevision);
    }

    @Override
    public RollbackHistory fetchRollbackHistory(String appName) {
        log.info("Fetching rollback history from ArgoCD for appName={}", appName);

        JsonNode json = fetchApplication(appName);
        return parseRollbackHistory(json);
    }

    @Override
    public List<DeploySnapshot> fetchDeploysInWindow(String appName, Instant from, Instant to) {
        log.info(
                "Fetching deploys in window from ArgoCD for appName={} from={} to={}",
                appName,
                from,
                to);

        JsonNode json = fetchApplication(appName);
        return parseDeploysInWindow(json, appName, from, to);
    }

    private JsonNode fetchApplication(String appName) {
        try {
            JsonNode json =
                    argocdWebClient
                            .get()
                            .uri("/api/v1/applications/{appName}", appName)
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

            if (json == null) {
                throw new ResourceNotFoundException("ArgoCD application not found: " + appName);
            }

            return json;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException(
                        "ArgoCD application not found: " + appName);
            }
            throw new ServiceUnavailableException(
                    "ArgoCD service error: HTTP " + e.getStatusCode().value());
        }
    }

    private JsonNode fetchRevisionMetadata(String appName, String revision) {
        try {
            JsonNode json =
                    argocdWebClient
                            .get()
                            .uri(
                                    "/api/v1/applications/{appName}/revisions/{revision}/metadata",
                                    appName,
                                    revision)
                            .retrieve()
                            .onStatus(
                                    HttpStatusCode::is4xxClientError,
                                    response -> {
                                        throw new ResourceNotFoundException(
                                                "ArgoCD revision not found: " + revision);
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

            if (json == null) {
                throw new ResourceNotFoundException("ArgoCD revision not found: " + revision);
            }

            return json;
        } catch (WebClientResponseException e) {
            if (e.getStatusCode().is4xxClientError()) {
                throw new ResourceNotFoundException("ArgoCD revision not found: " + revision);
            }
            throw new ServiceUnavailableException(
                    "ArgoCD service error: HTTP " + e.getStatusCode().value());
        }
    }

    private DeploySnapshot parseDeploySnapshot(JsonNode json) {
        String name = json.path("metadata").path("name").asText("unknown");

        JsonNode status = json.path("status");
        String syncStatus = status.path("sync").path("status").asText("Unknown");
        String revision = status.path("sync").path("revision").asText("");
        String health = status.path("health").path("status").asText("Unknown");

        Instant deployedAt = Instant.now();
        JsonNode operationState = status.get("operationState");
        if (operationState != null && operationState.has("finishedAt")) {
            deployedAt = Instant.parse(operationState.get("finishedAt").asText());
        }

        List<String> images = new ArrayList<>();
        JsonNode summary = status.get("summary");
        if (summary != null) {
            JsonNode imagesNode = summary.get("images");
            if (imagesNode != null && imagesNode.isArray()) {
                for (JsonNode img : imagesNode) {
                    images.add(img.asText());
                }
            }
        }

        String author = "unknown";
        JsonNode operation = json.get("operation");
        if (operation != null) {
            JsonNode info = operation.get("info");
            if (info != null && info.isArray()) {
                for (JsonNode entry : info) {
                    if ("author".equals(entry.path("name").asText())) {
                        author = entry.path("value").asText("unknown");
                    }
                }
            }
        }

        Duration timeSinceDeploy = Duration.between(deployedAt, Instant.now());

        return new DeploySnapshot(
                name, revision, revision, author, deployedAt, syncStatus, health,
                timeSinceDeploy, images);
    }

    private RollbackHistory parseRollbackHistory(JsonNode json) {
        JsonNode historyNode = json.path("status").get("history");

        if (historyNode == null || !historyNode.isArray() || historyNode.isEmpty()) {
            return new RollbackHistory(List.of(), false, "", "");
        }

        List<String> allRevisions = new ArrayList<>();
        for (JsonNode entry : historyNode) {
            allRevisions.add(entry.path("revision").asText());
        }

        String currentRevision = allRevisions.getLast();
        List<String> previousRevisions =
                allRevisions.size() > 1
                        ? allRevisions.subList(0, allRevisions.size() - 1).reversed()
                        : List.of();
        boolean canRollback = !previousRevisions.isEmpty();
        String lastStableRevision = canRollback ? previousRevisions.getFirst() : "";

        return new RollbackHistory(previousRevisions, canRollback, currentRevision,
                lastStableRevision);
    }

    private List<DeploySnapshot> parseDeploysInWindow(
            JsonNode json, String appName, Instant from, Instant to) {
        List<DeploySnapshot> deploys = new ArrayList<>();

        JsonNode historyNode = json.path("status").get("history");
        if (historyNode == null || !historyNode.isArray()) {
            return deploys;
        }

        String syncStatus = json.path("status").path("sync").path("status").asText("Unknown");
        String health = json.path("status").path("health").path("status").asText("Unknown");

        for (JsonNode entry : historyNode) {
            if (!entry.has("deployedAt")) {
                continue;
            }

            Instant deployedAt = Instant.parse(entry.get("deployedAt").asText());

            if (!deployedAt.isBefore(from) && !deployedAt.isAfter(to)) {
                String revision = entry.path("revision").asText("");
                Duration timeSinceDeploy = Duration.between(deployedAt, Instant.now());

                deploys.add(new DeploySnapshot(
                        appName, revision, revision, "unknown", deployedAt,
                        syncStatus, health, timeSinceDeploy, List.of()));
            }
        }

        return deploys;
    }

    private String findPreviousRevision(JsonNode appJson, String currentRevision) {
        JsonNode historyNode = appJson.path("status").get("history");
        if (historyNode == null || !historyNode.isArray()) {
            return "";
        }

        String previousRevision = "";
        for (JsonNode entry : historyNode) {
            String rev = entry.path("revision").asText("");
            if (rev.equals(currentRevision)) {
                return previousRevision;
            }
            previousRevision = rev;
        }

        return "";
    }
}
