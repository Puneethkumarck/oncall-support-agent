package com.stablebridge.oncall.agent.trace;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.annotation.Export;
import com.embabel.agent.api.common.Ai;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.persona.OnCallPersonas;
import com.stablebridge.oncall.domain.model.logs.LogCluster;
import com.stablebridge.oncall.domain.model.metrics.MetricsSnapshot;
import com.stablebridge.oncall.domain.model.trace.CallChainStep;
import com.stablebridge.oncall.domain.model.trace.TraceAnalysisReport;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.port.tempo.TraceProvider;
import com.stablebridge.oncall.domain.service.TraceAnalysisReportFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Agent(
        description =
                "Analyze distributed traces to pinpoint bottleneck services in cross-service request chains")
@Slf4j
@RequiredArgsConstructor
public class TraceAnalysisAgent {

    private final TraceProvider traceProvider;
    private final MetricsProvider metricsProvider;
    private final LogSearchProvider logSearchProvider;
    private final TraceAnalysisReportFormatter traceAnalysisReportFormatter;

    // Blackboard state records
    public record TraceQuery(String service, String traceId, Instant from, Instant to) {}

    public record TraceData(List<CallChainStep> spans) {}

    public record ServiceMetricsData(MetricsSnapshot snapshot) {}

    public record ServiceLogsData(List<LogCluster> clusters) {}

    public record FormattedTraceAnalysis(
            String service, TraceAnalysisReport report, String markdown) {}

    @Action(description = "Parse trace analysis request into structured query")
    public TraceQuery parseTraceQuery(UserInput userInput) {
        var content = userInput.getContent().trim();
        log.info("Parsing trace query from input: {}", content);

        String service;
        String traceId = null;
        int windowMinutes = 30;

        var parts = content.split("\\s+");
        service = parts[0];
        if (parts.length > 1) {
            traceId = parts[1];
        }
        if (parts.length > 2) {
            try {
                windowMinutes = Integer.parseInt(parts[2]);
            } catch (NumberFormatException e) {
                log.debug(
                        "Could not parse time window '{}', using default 30m",
                        parts[2]);
            }
        }

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofMinutes(windowMinutes));
        return new TraceQuery(service, traceId, from, to);
    }

    @Action(description = "Fetch distributed traces from Tempo for the given service")
    public TraceData fetchDistributedTraces(TraceQuery query) {
        log.info("Fetching distributed traces for service: {}", query.service());
        List<CallChainStep> spans;
        if (query.traceId() != null) {
            spans = traceProvider.fetchTrace(query.traceId());
        } else {
            spans =
                    traceProvider.searchTraces(
                            query.service(), query.from(), query.to(), 20);
        }
        return new TraceData(spans);
    }

    @Action(description = "Fetch per-service metrics from Prometheus for the traced service")
    public ServiceMetricsData fetchPerServiceMetrics(TraceQuery query) {
        log.info("Fetching per-service metrics for service: {}", query.service());
        var metrics = metricsProvider.fetchServiceMetrics(query.service(), query.to());
        return new ServiceMetricsData(metrics);
    }

    @Action(description = "Fetch per-service error logs from Loki for the traced service")
    public ServiceLogsData fetchPerServiceLogs(TraceQuery query) {
        log.info("Fetching per-service logs for service: {}", query.service());
        var clusters =
                logSearchProvider.searchLogs(
                        query.service(), query.from(), query.to(), "ERROR");
        return new ServiceLogsData(clusters);
    }

    @Action(
            description =
                    "Analyze call chain to identify bottleneck using Senior SRE persona")
    public TraceAnalysisReport analyzeCallChain(
            TraceData traceData,
            ServiceMetricsData metricsData,
            ServiceLogsData logsData,
            TraceQuery query,
            Ai ai) {
        log.info("Analyzing call chain for service: {}", query.service());
        String prompt =
                buildAnalysisPrompt(query, traceData, metricsData, logsData);
        return ai.withDefaultLlm()
                .withPromptContributor(OnCallPersonas.SENIOR_SRE)
                .creating(TraceAnalysisReport.class)
                .fromPrompt(prompt);
    }

    @AchievesGoal(
            description = "Produce formatted trace analysis report",
            export =
                    @Export(
                            name = "analyzeTraces",
                            remote = true,
                            startingInputTypes = {UserInput.class}))
    @Action(description = "Format trace analysis report as markdown")
    public FormattedTraceAnalysis formatReport(
            TraceAnalysisReport report, TraceQuery query) {
        log.info(
                "Formatting trace analysis report for service: {} — {} steps, bottleneck: {}",
                query.service(),
                report.callChain().size(),
                report.bottleneck() != null
                        ? report.bottleneck().service()
                        : "none");
        String markdown =
                traceAnalysisReportFormatter.format(query.service(), report);
        return new FormattedTraceAnalysis(query.service(), report, markdown);
    }

    private String buildAnalysisPrompt(
            TraceQuery query,
            TraceData traceData,
            ServiceMetricsData metricsData,
            ServiceLogsData logsData) {
        int bottleneckCount =
                (int) traceData.spans().stream()
                        .filter(CallChainStep::isBottleneck)
                        .count();
        int errorLogCount =
                logsData.clusters().stream().mapToInt(LogCluster::count).sum();
        int newPatternCount =
                (int) logsData.clusters().stream()
                        .filter(LogCluster::isNew)
                        .count();

        return String.format(
                """
                Analyze the distributed traces for service '%s' and produce a TraceAnalysisReport.

                ## Trace Query
                - Service: %s
                - Trace ID: %s
                - Time Window: %s to %s

                ## Call Chain (%d spans, %d flagged as bottleneck)
                %s

                ## Service Metrics
                - Error Rate: %.4f (%.2f%%)
                - Latency P50: %.1fms | P95: %.1fms | P99: %.1fms
                - Throughput: %.0f req/s
                - CPU: %.1f%% | Memory: %.1f%% | Saturation: %.2f

                ## Error Logs (%d total errors, %d new patterns)
                %s

                Provide your analysis as a TraceAnalysisReport with:
                1. callChain: ordered list of services in the request path with operation, duration, status, and isBottleneck flag
                2. bottleneck: the SINGLE service causing the most impact with reason and evidence
                3. cascadeImpact: list of downstream services affected by the bottleneck with impact type and latency increase
                4. recommendation: ONE specific actionable recommendation to resolve the bottleneck
                """,
                query.service(),
                query.service(),
                query.traceId() != null ? query.traceId() : "N/A (search mode)",
                query.from(),
                query.to(),
                traceData.spans().size(),
                bottleneckCount,
                formatSpans(traceData),
                metricsData.snapshot().errorRate(),
                metricsData.snapshot().errorRate() * 100,
                metricsData.snapshot().latencyP50(),
                metricsData.snapshot().latencyP95(),
                metricsData.snapshot().latencyP99(),
                metricsData.snapshot().throughput(),
                metricsData.snapshot().cpuPercent(),
                metricsData.snapshot().memoryPercent(),
                metricsData.snapshot().saturation(),
                errorLogCount,
                newPatternCount,
                formatLogs(logsData));
    }

    private String formatSpans(TraceData traceData) {
        if (traceData.spans().isEmpty()) {
            return "No trace spans found.";
        }
        var sb = new StringBuilder();
        for (var span : traceData.spans()) {
            sb.append(
                    String.format(
                            "- %s `%s`: %dms [%s]%s%n",
                            span.service(),
                            span.operation(),
                            span.durationMs(),
                            span.status(),
                            span.isBottleneck() ? " **BOTTLENECK**" : ""));
        }
        return sb.toString();
    }

    private String formatLogs(ServiceLogsData logsData) {
        if (logsData.clusters().isEmpty()) {
            return "No error logs found.";
        }
        var sb = new StringBuilder();
        for (var cluster : logsData.clusters()) {
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
}
