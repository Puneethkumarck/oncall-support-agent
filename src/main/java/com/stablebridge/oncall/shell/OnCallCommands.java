package com.stablebridge.oncall.shell;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent.FormattedDeployImpact;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent.FormattedFatigueReport;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent.FormattedHealthCard;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent.FormattedLogAnalysis;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.FormattedPostMortem;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent.FormattedSLOReport;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent.FormattedTraceAnalysis;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent.FormattedTriageReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellOption;

@ShellComponent
@Slf4j
@RequiredArgsConstructor
public class OnCallCommands {

    private final AgentPlatform agentPlatform;
    private final IncidentTriageAgent incidentTriageAgent;
    private final ServiceHealthAgent serviceHealthAgent;
    private final DeployImpactAgent deployImpactAgent;
    private final LogAnalysisAgent logAnalysisAgent;
    private final TraceAnalysisAgent traceAnalysisAgent;
    private final SLOMonitorAgent sloMonitorAgent;
    private final AlertFatigueAgent alertFatigueAgent;
    private final PostMortemAgent postMortemAgent;

    @ShellMethod(key = "triage", value = "Trigger incident triage for an alert (UC-1)")
    public String triage(String alertId) {
        log.info("Shell: triggering incident triage for alertId={}", alertId);
        var result =
                AgentInvocation.create(agentPlatform, FormattedTriageReport.class)
                        .invoke(new UserInput(alertId), incidentTriageAgent);
        return result.markdown();
    }

    @ShellMethod(key = "health", value = "Produce service health card (UC-4)")
    public String health(String service) {
        log.info("Shell: checking health for service={}", service);
        var result =
                AgentInvocation.create(agentPlatform, FormattedHealthCard.class)
                        .invoke(new UserInput(service), serviceHealthAgent);
        return result.markdown();
    }

    @ShellMethod(
            key = "deploy-impact",
            value = "Analyze deployment impact for a service (UC-3)")
    public String deployImpact(String service) {
        log.info("Shell: analyzing deploy impact for service={}", service);
        var result =
                AgentInvocation.create(agentPlatform, FormattedDeployImpact.class)
                        .invoke(new UserInput(service), deployImpactAgent);
        return result.markdown();
    }

    @ShellMethod(key = "logs", value = "Analyze log patterns for a service (UC-2)")
    public String logs(String service) {
        log.info("Shell: analyzing logs for service={}", service);
        var result =
                AgentInvocation.create(agentPlatform, FormattedLogAnalysis.class)
                        .invoke(new UserInput(service), logAnalysisAgent);
        return result.formattedBody();
    }

    @ShellMethod(key = "trace", value = "Analyze cross-service traces (UC-5)")
    public String trace(
            String service,
            @ShellOption(defaultValue = ShellOption.NULL) String traceId) {
        String input = traceId != null ? service + " " + traceId : service;
        log.info(
                "Shell: analyzing traces for service={}, traceId={}",
                service,
                traceId);
        var result =
                AgentInvocation.create(agentPlatform, FormattedTraceAnalysis.class)
                        .invoke(new UserInput(input), traceAnalysisAgent);
        return result.markdown();
    }

    @ShellMethod(key = "slo", value = "Check SLO burn rate for a service (UC-10)")
    public String slo(String service) {
        log.info("Shell: checking SLO for service={}", service);
        var result =
                AgentInvocation.create(agentPlatform, FormattedSLOReport.class)
                        .invoke(new UserInput(service), sloMonitorAgent);
        return result.markdown();
    }

    @ShellMethod(
            key = "alert-fatigue",
            value = "Analyze alert noise for a team over N days (UC-8)")
    public String alertFatigue(String team, int days) {
        log.info(
                "Shell: analyzing alert fatigue for team={}, days={}", team, days);
        var result =
                AgentInvocation.create(agentPlatform, FormattedFatigueReport.class)
                        .invoke(
                                new UserInput(team + " " + days),
                                alertFatigueAgent);
        return result.markdown();
    }

    @ShellMethod(key = "postmortem", value = "Generate post-mortem draft for an incident (UC-9)")
    public String postmortem(
            String incidentId,
            @ShellOption(defaultValue = ShellOption.NULL) String service) {
        String input = service != null ? incidentId + " " + service : incidentId;
        log.info(
                "Shell: generating post-mortem for incidentId={}, service={}",
                incidentId,
                service);
        var result =
                AgentInvocation.create(agentPlatform, FormattedPostMortem.class)
                        .invoke(new UserInput(input), postMortemAgent);
        return result.markdown();
    }
}
