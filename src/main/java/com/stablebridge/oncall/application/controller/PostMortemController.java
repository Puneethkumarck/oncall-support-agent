package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.FormattedPostMortem;
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
public class PostMortemController {

    private final AgentPlatform agentPlatform;
    private final PostMortemAgent postMortemAgent;

    public record PostMortemRequest(String incidentId, String service) {}

    @PostMapping("/postmortem")
    public FormattedPostMortem generatePostMortem(@RequestBody PostMortemRequest request) {
        log.info(
                "Received post-mortem request for incident: {}, service: {}",
                request.incidentId(),
                request.service());

        String content = request.incidentId() + " " + request.service();

        var invocation = AgentInvocation.create(agentPlatform, FormattedPostMortem.class);
        return invocation.invoke(new UserInput(content), postMortemAgent);
    }
}
