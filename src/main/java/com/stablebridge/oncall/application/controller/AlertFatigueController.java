package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent.FormattedFatigueReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AlertFatigueController {

    private final AgentPlatform agentPlatform;
    private final AlertFatigueAgent alertFatigueAgent;

    @GetMapping("/alert-fatigue")
    public FormattedFatigueReport analyzeAlertFatigue(
            @RequestParam(defaultValue = "platform") String team,
            @RequestParam(defaultValue = "7") int days) {
        log.info("Received alert fatigue request for team: {}, days: {}", team, days);

        String content = team + " " + days;

        var invocation = AgentInvocation.create(agentPlatform, FormattedFatigueReport.class);
        return invocation.invoke(new UserInput(content), alertFatigueAgent);
    }
}
