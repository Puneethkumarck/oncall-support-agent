package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.FormattedDeployImpact;
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
public class DeployController {

    private final AgentPlatform agentPlatform;
    private final DeployImpactAgent deployImpactAgent;

    public record DeployImpactRequest(String service, String deployId) {}

    @PostMapping("/deploy-impact")
    public FormattedDeployImpact analyzeDeployImpact(@RequestBody DeployImpactRequest request) {
        log.info(
                "Received deploy impact request for service: {}, deployId: {}",
                request.service(),
                request.deployId());

        String userInputContent = request.service();
        if (request.deployId() != null && !request.deployId().isBlank()) {
            userInputContent += " " + request.deployId();
        }

        var invocation = AgentInvocation.create(agentPlatform, FormattedDeployImpact.class);
        return invocation.invoke(new UserInput(userInputContent), deployImpactAgent);
    }
}
