package com.stablebridge.oncall.agent.postmortem;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.deploy.DeploySnapshot;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.model.postmortem.PostMortemDraft;
import com.stablebridge.oncall.domain.model.postmortem.TimelineEntry;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.pagerduty.IncidentTimelineProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.PostMortemFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(description = "Generate blameless post-mortem draft from incident data")
@Slf4j
@RequiredArgsConstructor
public class PostMortemAgent {

    private final IncidentTimelineProvider incidentTimelineProvider;
    private final LogSearchProvider logSearchProvider;
    private final MetricsProvider metricsProvider;
    private final DeployHistoryProvider deployHistoryProvider;
    private final PostMortemFormatter postMortemFormatter;

    // Blackboard state records
    public record PostMortemQuery(String incidentId, String service) {}

    public record IncidentTimeline(List<TimelineEntry> entries) {}

    public record IncidentLogSnapshot(List<LogCluster> clusters) {}

    public record IncidentMetricsSnapshot(MetricsSnapshot snapshot) {}

    public record DeployEventSnapshot(List<DeploySnapshot> deploys) {}

    public record FormattedPostMortem(
            String incidentId, PostMortemDraft draft, String markdown) {}

    @Action(description = "Parse post-mortem request into incident identity and service")
    public PostMortemQuery parseRequest(UserInput userInput) {
        var content = userInput.getContent().trim();
        var parts = content.split("\\s+", 2);
        var incidentId = parts[0];
        var service = parts.length > 1 ? parts[1] : incidentId;
        log.info(
                "Parsing post-mortem request for incident: {}, service: {}",
                incidentId,
                service);
        return new PostMortemQuery(incidentId, service);
    }

    @Action(description = "Fetch incident timeline from PagerDuty")
    public IncidentTimeline fetchIncidentTimeline(PostMortemQuery query) {
        log.info("Fetching incident timeline for incident: {}", query.incidentId());
        var entries = incidentTimelineProvider.fetchIncidentTimeline(query.incidentId());
        return new IncidentTimeline(entries);
    }

    @Action(description = "Fetch incident logs from Loki")
    public IncidentLogSnapshot fetchIncidentLogs(PostMortemQuery query) {
        log.info("Fetching incident logs for service: {}", query.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(4));
        return new IncidentLogSnapshot(
                logSearchProvider.searchLogs(query.service(), from, to, "ERROR"));
    }

    @Action(description = "Fetch incident metrics from Prometheus")
    public IncidentMetricsSnapshot fetchIncidentMetrics(PostMortemQuery query) {
        log.info("Fetching incident metrics for service: {}", query.service());
        var metrics = metricsProvider.fetchServiceMetrics(query.service(), Instant.now());
        return new IncidentMetricsSnapshot(metrics);
    }

    @Action(description = "Fetch deploy events from ArgoCD")
    public DeployEventSnapshot fetchDeployEvents(PostMortemQuery query) {
        log.info("Fetching deploy events for service: {}", query.service());
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(24));
        return new DeployEventSnapshot(
                deployHistoryProvider.fetchDeploysInWindow(query.service(), from, to));
    }

    @Action(
            description =
                    "Draft post-mortem using Incident Commander persona based on collected data")
    public PostMortemDraft draftPostMortem(
            IncidentTimeline timeline,
            IncidentLogSnapshot logs,
            IncidentMetricsSnapshot metrics,
            DeployEventSnapshot deploys,
            PostMortemQuery query,
            Ai ai) {
        log.info("Drafting post-mortem for incident: {}", query.incidentId());
        String prompt =
                buildPostMortemPrompt(
                        query.incidentId(),
                        query.service(),
                        timeline,
                        logs,
                        metrics,
                        deploys);
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.INCIDENT_COMMANDER)
                .creating(PostMortemDraft.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted post-mortem document",
            export =
                    @Export(
                            name = "generatePostMortem",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format post-mortem draft as markdown document")
    public FormattedPostMortem formatPostMortem(
            PostMortemDraft draft, PostMortemQuery query) {
        log.info(
                "Formatting post-mortem for incident: {} — severity: {}",
                query.incidentId(),
                draft.severity());
        String markdown = postMortemFormatter.format(draft);
        return new FormattedPostMortem(query.incidentId(), draft, markdown);
    }

    private String buildPostMortemPrompt(
            String incidentId,
            String service,
            IncidentTimeline timeline,
            IncidentLogSnapshot logs,
            IncidentMetricsSnapshot metrics,
            DeployEventSnapshot deploys) {
        return String.format(
                """
                Generate a blameless post-mortem draft for incident '%s' on service '%s'.

                ## Incident Timeline (%d entries)
                %s

                ## Error Logs (%d clusters)
                %s

                ## Current Metrics
                - Error Rate: %.4f (%.2f%%)
                - Latency P50: %.1fms | P95: %.1fms | P99: %.1fms
                - Throughput: %.0f req/s
                - CPU: %.1f%% | Memory: %.1f%%

                ## Recent Deploys (%d in last 24h)
                %s

                Provide your post-mortem as a PostMortemDraft with:
                1. title: A concise incident title including severity and root cause
                2. severity: SEV1-SEV5 based on impact
                3. impact: duration, affected users estimate, and affected services
                4. timeline: ordered list of key events with timestamps
                5. rootCause: ONE specific root cause with evidence
                6. contributingFactors: systemic factors that allowed the incident
                7. whatWentWell: things that helped during incident response
                8. whatWentPoorly: things that hindered incident response
                9. actionItems: specific, assigned, time-bound action items with priority (P1-P3)
                10. lessonsLearned: key takeaways for the team
                """,
                incidentId,
                service,
                timeline.entries().size(),
                formatTimeline(timeline),
                logs.clusters().size(),
                formatLogs(logs),
                metrics.snapshot().errorRate(),
                metrics.snapshot().errorRate() * 100,
                metrics.snapshot().latencyP50(),
                metrics.snapshot().latencyP95(),
                metrics.snapshot().latencyP99(),
                metrics.snapshot().throughput(),
                metrics.snapshot().cpuPercent(),
                metrics.snapshot().memoryPercent(),
                deploys.deploys().size(),
                formatDeploys(deploys));
    }

    private String formatTimeline(IncidentTimeline timeline) {
        if (timeline.entries().isEmpty()) {
            return "No timeline entries available.";
        }
        var sb = new StringBuilder();
        for (var entry : timeline.entries()) {
            sb.append(
                    String.format(
                            "- [%s] %s (%s)%n",
                            entry.timestamp(), entry.event(), entry.actor()));
        }
        return sb.toString();
    }

    private String formatLogs(IncidentLogSnapshot logs) {
        if (logs.clusters().isEmpty()) {
            return "No error clusters found.";
        }
        var sb = new StringBuilder();
        for (var cluster : logs.clusters()) {
            sb.append(
                    String.format(
                            "- %s (%s): %d occurrences%s%n",
                            cluster.exceptionType(),
                            cluster.fingerprint(),
                            cluster.count(),
                            cluster.isNew() ? " [NEW]" : ""));
        }
        return sb.toString();
    }

    private String formatDeploys(DeployEventSnapshot deploys) {
        if (deploys.deploys().isEmpty()) {
            return "No recent deploys found.";
        }
        var sb = new StringBuilder();
        for (var deploy : deploys.deploys()) {
            sb.append(
                    String.format(
                            "- %s by %s at %s [%s/%s]%n",
                            deploy.lastDeployId(),
                            deploy.author(),
                            deploy.deployedAt(),
                            deploy.syncStatus(),
                            deploy.health()));
        }
        return sb.toString();
    }
}
