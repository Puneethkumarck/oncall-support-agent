package com.stablebridge.oncall.shell;

import com.embabel.agent.api.common.AgentPlatform;
import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.deploy.DeployImpactAgent;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent;
import com.stablebridge.oncall.agent.health.ServiceHealthAgent;
import com.stablebridge.oncall.agent.logs.LogAnalysisAgent;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent;
import com.stablebridge.oncall.agent.slo.SLOMonitorAgent;
import com.stablebridge.oncall.agent.trace.TraceAnalysisAgent;
import com.stablebridge.oncall.agent.triage.IncidentTriageAgent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.stablebridge.oncall.fixtures.TestConstants.ALERT_ID;
import static com.stablebridge.oncall.fixtures.TestConstants.INCIDENT_ID;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static com.stablebridge.oncall.fixtures.TestConstants.TEAM_NAME;
import static com.stablebridge.oncall.fixtures.TestConstants.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class OnCallCommandsTest {

    @Mock private AgentPlatform agentPlatform;
    @Mock private IncidentTriageAgent incidentTriageAgent;
    @Mock private ServiceHealthAgent serviceHealthAgent;
    @Mock private DeployImpactAgent deployImpactAgent;
    @Mock private LogAnalysisAgent logAnalysisAgent;
    @Mock private TraceAnalysisAgent traceAnalysisAgent;
    @Mock private SLOMonitorAgent sloMonitorAgent;
    @Mock private AlertFatigueAgent alertFatigueAgent;
    @Mock private PostMortemAgent postMortemAgent;

    @Test
    @DisplayName("Constructor accepts all required dependencies")
    void shouldConstructWithAllDependencies() {
        // when
        var commands =
                new OnCallCommands(
                        agentPlatform,
                        incidentTriageAgent,
                        serviceHealthAgent,
                        deployImpactAgent,
                        logAnalysisAgent,
                        traceAnalysisAgent,
                        sloMonitorAgent,
                        alertFatigueAgent,
                        postMortemAgent);

        // then
        assertThat(commands).isNotNull();
    }

    @Test
    @DisplayName("triage agent parses alertId from UserInput correctly")
    void shouldConstructTriageInput() {
        // given — verify agent correctly parses what the shell command would send
        var triageAgent =
                new IncidentTriageAgent(null, null, null, null, null, null);

        // when
        var alert = triageAgent.parseAlert(new UserInput(ALERT_ID));

        // then
        assertThat(alert.service()).isEqualTo(ALERT_ID);
    }

    @Test
    @DisplayName("health agent parses service from UserInput correctly")
    void shouldConstructHealthInput() {
        // given
        var healthAgent = new ServiceHealthAgent(null, null, null, null);

        // when
        var query = healthAgent.parseHealthQuery(new UserInput(SERVICE_NAME));

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("deploy-impact agent parses service from UserInput correctly")
    void shouldConstructDeployImpactInput() {
        // given
        var deployAgent = new DeployImpactAgent(null, null, null, null);

        // when
        var query = deployAgent.parseDeployQuery(new UserInput(SERVICE_NAME));

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
        assertThat(query.lookbackMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("logs agent parses service from UserInput correctly")
    void shouldConstructLogsInput() {
        // given
        var logsAgent = new LogAnalysisAgent(null, null, null, null);

        // when
        var query = logsAgent.parseLogQuery(new UserInput(SERVICE_NAME));

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
        assertThat(query.severityFilter()).isEqualTo("ERROR");
    }

    @Test
    @DisplayName("trace agent parses service only from UserInput correctly")
    void shouldConstructTraceInputWithoutTraceId() {
        // given
        var traceAgent = new TraceAnalysisAgent(null, null, null, null);

        // when — mirrors what trace(service, null) sends
        var query = traceAgent.parseTraceQuery(new UserInput(SERVICE_NAME));

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
        assertThat(query.traceId()).isNull();
    }

    @Test
    @DisplayName("trace agent parses service and traceId from UserInput correctly")
    void shouldConstructTraceInputWithTraceId() {
        // given
        var traceAgent = new TraceAnalysisAgent(null, null, null, null);

        // when — mirrors what trace(service, traceId) sends
        var query =
                traceAgent.parseTraceQuery(
                        new UserInput(SERVICE_NAME + " " + TRACE_ID));

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
        assertThat(query.traceId()).isEqualTo(TRACE_ID);
    }

    @Test
    @DisplayName("slo agent parses service from UserInput correctly")
    void shouldConstructSloInput() {
        // given
        var sloAgent = new SLOMonitorAgent(null, null, null);

        // when
        var query = sloAgent.parseSLOQuery(new UserInput(SERVICE_NAME));

        // then
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("alert-fatigue agent parses team and days from UserInput correctly")
    void shouldConstructAlertFatigueInput() {
        // given
        var fatigueAgent = new AlertFatigueAgent(null, null);

        // when — mirrors what alertFatigue(team, 7) sends
        var query =
                fatigueAgent.parseRequest(new UserInput(TEAM_NAME + " 7"));

        // then
        assertThat(query.team()).isEqualTo(TEAM_NAME);
        assertThat(query.days()).isEqualTo(7);
    }

    @Test
    @DisplayName("postmortem agent parses incidentId only from UserInput correctly")
    void shouldConstructPostmortemInputWithoutService() {
        // given
        var postmortemAgent =
                new PostMortemAgent(null, null, null, null, null);

        // when — mirrors what postmortem(incidentId, null) sends
        var query =
                postmortemAgent.parseRequest(new UserInput(INCIDENT_ID));

        // then
        assertThat(query.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(query.service()).isEqualTo(INCIDENT_ID);
    }

    @Test
    @DisplayName("postmortem agent parses incidentId and service from UserInput correctly")
    void shouldConstructPostmortemInputWithService() {
        // given
        var postmortemAgent =
                new PostMortemAgent(null, null, null, null, null);

        // when — mirrors what postmortem(incidentId, service) sends
        var query =
                postmortemAgent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // then
        assertThat(query.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(query.service()).isEqualTo(SERVICE_NAME);
    }
}
