package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.FormattedTraceAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class TraceController {

    private final AgentPlatform agentPlatform;
    private final TraceAnalysisAgent traceAnalysisAgent;

    public record TraceAnalysisRequest(String service, String traceId) {}

    @PostMapping("/trace-analysis")
    public FormattedTraceAnalysis analyzeTraces(@RequestBody TraceAnalysisRequest request) {
        log.info(
                "Received trace analysis request for service: {}, traceId: {}",
                request.service(),
                request.traceId());

        var sb = new StringBuilder(request.service());
        if (request.traceId() != null && !request.traceId().isBlank()) {
            sb.append(" ").append(request.traceId());
        }

        var invocation = AgentInvocation.create(agentPlatform, FormattedTraceAnalysis.class);
        return invocation.invoke(new UserInput(sb.toString()), traceAnalysisAgent);
    }
}
