package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.FormattedLogAnalysis;
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
public class LogAnalysisController {

    private final AgentPlatform agentPlatform;
    private final LogAnalysisAgent logAnalysisAgent;

    public record LogAnalysisRequest(String service, String timeWindow, String severity) {}

    @PostMapping("/log-analysis")
    public FormattedLogAnalysis analyzeLogPatterns(@RequestBody LogAnalysisRequest request) {
        log.info(
                "Received log analysis request for service: {}, window: {}, severity: {}",
                request.service(),
                request.timeWindow(),
                request.severity());

        var sb = new StringBuilder(request.service());
        if (request.timeWindow() != null && !request.timeWindow().isBlank()) {
            sb.append(" ").append(request.timeWindow());
        }
        if (request.severity() != null && !request.severity().isBlank()) {
            sb.append(" ").append(request.severity());
        }

        var invocation = AgentInvocation.create(agentPlatform, FormattedLogAnalysis.class);
        return invocation.invoke(new UserInput(sb.toString()), logAnalysisAgent);
    }
}
