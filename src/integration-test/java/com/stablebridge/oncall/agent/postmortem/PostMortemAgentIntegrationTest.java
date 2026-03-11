package com.stablebridge.oncall.agent.postmortem;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.model.postmortem.PostMortemDraft;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.pagerduty.IncidentTimelineProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.List;

import static com.stablebridge.oncall.fixtures.DeployFixtures.aDeploySnapshot;
import static com.stablebridge.oncall.fixtures.LogFixtures.aLogCluster;
import static com.stablebridge.oncall.fixtures.MetricsFixtures.aMetricsSnapshot;
import static com.stablebridge.oncall.fixtures.PostMortemFixtures.aPostMortemDraft;
import static com.stablebridge.oncall.fixtures.PostMortemFixtures.aTimelineEntry;
import static com.stablebridge.oncall.fixtures.TestConstants.INCIDENT_ID;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@Import(TestJacksonConfig.class)
class PostMortemAgentIntegrationTest extends EmbabelMockitoIntegrationTest {

    private IncidentTimelineProvider incidentTimelineProvider;
    private LogSearchProvider logSearchProvider;
    private MetricsProvider metricsProvider;
    private DeployHistoryProvider deployHistoryProvider;

    private PostMortemAgent agent;

    @BeforeEach
    void setUpMocks() {
        incidentTimelineProvider = Mockito.mock(IncidentTimelineProvider.class);
        logSearchProvider = Mockito.mock(LogSearchProvider.class);
        metricsProvider = Mockito.mock(MetricsProvider.class);
        deployHistoryProvider = Mockito.mock(DeployHistoryProvider.class);

        agent =
                new PostMortemAgent(
                        incidentTimelineProvider,
                        logSearchProvider,
                        metricsProvider,
                        deployHistoryProvider,
                        new com.stablebridge.oncall.domain.service.PostMortemFormatter());
    }

    private void stubAllPorts() {
        given(incidentTimelineProvider.fetchIncidentTimeline(INCIDENT_ID))
                .willReturn(List.of(aTimelineEntry()));
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of(aLogCluster()));
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(aMetricsSnapshot());
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of(aDeploySnapshot()));
    }

    @Test
    @DisplayName(
            "Full GOAP chain via AgentInvocation: UserInput → data fetch → LLM draft → formatted post-mortem")
    void shouldExecuteFullGoapChain() {
        // given — stub all external ports
        stubAllPorts();

        // given — stub LLM to return fixture post-mortem draft
        whenCreateObject(
                        prompt -> prompt.contains(INCIDENT_ID), PostMortemDraft.class)
                .thenReturn(aPostMortemDraft());

        // when — run agent through AgentPlatform GOAP planner
        var invocation =
                AgentInvocation.create(
                        agentPlatform, PostMortemAgent.FormattedPostMortem.class);
        var result =
                invocation.invoke(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME), agent);

        // then — verify the output
        assertThat(result).isNotNull();
        assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(result.draft().severity()).isEqualTo(IncidentSeverity.SEV2);
        assertThat(result.markdown()).contains("Post-Mortem");
        assertThat(result.markdown()).contains("SEV2");
        assertThat(result.markdown()).contains("Impact");
        assertThat(result.markdown()).contains("Timeline");
        assertThat(result.markdown()).contains("Root Cause");
        assertThat(result.markdown()).contains("Action Items");

        // then — verify LLM was called with correct context
        verifyCreateObject(
                prompt -> prompt.contains(INCIDENT_ID) && prompt.contains("blameless"),
                PostMortemDraft.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("All four data-fetch actions invoke their respective ports")
    void shouldFetchDataThroughAllPorts() {
        // given
        stubAllPorts();
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        agent.fetchIncidentTimeline(query);
        agent.fetchIncidentLogs(query);
        agent.fetchIncidentMetrics(query);
        agent.fetchDeployEvents(query);

        // then
        then(incidentTimelineProvider).should().fetchIncidentTimeline(INCIDENT_ID);
        then(logSearchProvider)
                .should()
                .searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR"));
        then(metricsProvider)
                .should()
                .fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class));
        then(deployHistoryProvider)
                .should()
                .fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class));
    }

    @Test
    @DisplayName("Formatted post-mortem contains all expected sections")
    void shouldProduceFormattedPostMortemWithAllSections() {
        // given
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));
        var draft = aPostMortemDraft();

        // when
        var result = agent.formatPostMortem(draft, query);

        // then
        assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(result.draft()).isEqualTo(draft);
        assertThat(result.markdown())
                .contains("Post-Mortem:")
                .contains("SEV2")
                .contains("Impact")
                .contains("Timeline")
                .contains("Root Cause")
                .contains("Contributing Factors")
                .contains("What Went Well")
                .contains("What Went Poorly")
                .contains("Action Items")
                .contains("Lessons Learned");
    }

    @Test
    @DisplayName("Incident timeline action returns entries from PagerDuty")
    void shouldFetchIncidentTimelineFromPagerDuty() {
        // given
        stubAllPorts();
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        var timeline = agent.fetchIncidentTimeline(query);

        // then
        assertThat(timeline.entries()).hasSize(1);
        assertThat(timeline.entries().getFirst().event())
                .contains("PagerDuty alert triggered");
        assertThat(timeline.entries().getFirst().actor()).isEqualTo("PagerDuty");
    }

    @Test
    @DisplayName("Incident logs action returns clusters from Loki")
    void shouldFetchIncidentLogsFromLoki() {
        // given
        stubAllPorts();
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        var logs = agent.fetchIncidentLogs(query);

        // then
        assertThat(logs.clusters()).hasSize(1);
        assertThat(logs.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(logs.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("Incident metrics action returns snapshot from Prometheus")
    void shouldFetchIncidentMetricsFromPrometheus() {
        // given
        stubAllPorts();
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        var metrics = agent.fetchIncidentMetrics(query);

        // then
        assertThat(metrics.snapshot().service()).isEqualTo(SERVICE_NAME);
        assertThat(metrics.snapshot().errorRate()).isEqualTo(0.05);
    }

    @Test
    @DisplayName("Deploy events action returns snapshots from ArgoCD")
    void shouldFetchDeployEventsFromArgoCD() {
        // given
        stubAllPorts();
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        var deploys = agent.fetchDeployEvents(query);

        // then
        assertThat(deploys.deploys()).hasSize(1);
        assertThat(deploys.deploys().getFirst().appName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("Graceful handling when no timeline entries exist")
    void shouldHandleEmptyTimeline() {
        // given
        given(incidentTimelineProvider.fetchIncidentTimeline(INCIDENT_ID))
                .willReturn(List.of());
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        var timeline = agent.fetchIncidentTimeline(query);

        // then
        assertThat(timeline.entries()).isEmpty();
    }

    @Test
    @DisplayName("Graceful handling when no error logs exist")
    void shouldHandleEmptyLogs() {
        // given
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of());
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        var logs = agent.fetchIncidentLogs(query);

        // then
        assertThat(logs.clusters()).isEmpty();
    }

    @Test
    @DisplayName("Graceful handling when no recent deploys exist")
    void shouldHandleEmptyDeploys() {
        // given
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());
        var query =
                agent.parseRequest(
                        new UserInput(INCIDENT_ID + " " + SERVICE_NAME));

        // when
        var deploys = agent.fetchDeployEvents(query);

        // then
        assertThat(deploys.deploys()).isEmpty();
    }
}
