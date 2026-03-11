package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.FormattedSLOReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SLOController {

    private final AgentPlatform agentPlatform;
    private final SLOMonitorAgent sloMonitorAgent;

    @GetMapping("/slo/{service}")
    public FormattedSLOReport checkSLO(@PathVariable String service) {
        log.info("Received SLO check request for service: {}", service);

        var invocation = AgentInvocation.create(agentPlatform, FormattedSLOReport.class);
        return invocation.invoke(new UserInput(service), sloMonitorAgent);
    }
}
