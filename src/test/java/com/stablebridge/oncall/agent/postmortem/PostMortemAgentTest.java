package com.stablebridge.oncall.agent.postmortem;

import com.embabel.agent.domain.io.UserInput;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.DeployEventSnapshot;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.IncidentLogSnapshot;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.IncidentMetricsSnapshot;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.IncidentTimeline;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.PostMortemQuery;
import com.stablebridge.oncall.domain.model.common.IncidentSeverity;
import com.stablebridge.oncall.domain.port.argocd.DeployHistoryProvider;
import com.stablebridge.oncall.domain.port.loki.LogSearchProvider;
import com.stablebridge.oncall.domain.port.pagerduty.IncidentTimelineProvider;
import com.stablebridge.oncall.domain.port.prometheus.MetricsProvider;
import com.stablebridge.oncall.domain.service.PostMortemFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostMortemAgentTest {

    @Mock private IncidentTimelineProvider incidentTimelineProvider;
    @Mock private LogSearchProvider logSearchProvider;
    @Mock private MetricsProvider metricsProvider;
    @Mock private DeployHistoryProvider deployHistoryProvider;
    @Mock private PostMortemFormatter postMortemFormatter;

    private PostMortemAgent agent;

    @BeforeEach
    void setUp() {
        agent =
                new PostMortemAgent(
                        incidentTimelineProvider,
                        logSearchProvider,
                        metricsProvider,
                        deployHistoryProvider,
                        postMortemFormatter);
    }

    @Test
    @DisplayName("parseRequest extracts incident ID and service from UserInput")
    void shouldParseRequest() {
        // given
        var userInput = new UserInput(INCIDENT_ID + " " + SERVICE_NAME);

        // when
        var result = agent.parseRequest(userInput);

        // then
        assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("parseRequest uses incident ID as service when only one token provided")
    void shouldUseIncidentIdAsServiceWhenSingleToken() {
        // given
        var userInput = new UserInput(INCIDENT_ID);

        // when
        var result = agent.parseRequest(userInput);

        // then
        assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(result.service()).isEqualTo(INCIDENT_ID);
    }

    @Test
    @DisplayName("parseRequest trims whitespace from input")
    void shouldTrimWhitespace() {
        // given
        var userInput = new UserInput("  " + INCIDENT_ID + " " + SERVICE_NAME + "  ");

        // when
        var result = agent.parseRequest(userInput);

        // then
        assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(result.service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchIncidentTimeline returns timeline entries from PagerDuty")
    void shouldFetchIncidentTimeline() {
        // given
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        var entries = List.of(aTimelineEntry());
        given(incidentTimelineProvider.fetchIncidentTimeline(INCIDENT_ID))
                .willReturn(entries);

        // when
        var result = agent.fetchIncidentTimeline(query);

        // then
        assertThat(result.entries()).hasSize(1);
        assertThat(result.entries().getFirst().event())
                .isEqualTo("PagerDuty alert triggered: High error rate on alert-api");
        assertThat(result.entries().getFirst().actor()).isEqualTo("PagerDuty");
    }

    @Test
    @DisplayName("fetchIncidentTimeline returns empty list when no timeline exists")
    void shouldReturnEmptyTimelineWhenNone() {
        // given
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        given(incidentTimelineProvider.fetchIncidentTimeline(INCIDENT_ID))
                .willReturn(List.of());

        // when
        var result = agent.fetchIncidentTimeline(query);

        // then
        assertThat(result.entries()).isEmpty();
    }

    @Test
    @DisplayName("fetchIncidentLogs returns log clusters from Loki")
    void shouldFetchIncidentLogs() {
        // given
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        var clusters = List.of(aLogCluster());
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(clusters);

        // when
        var result = agent.fetchIncidentLogs(query);

        // then
        assertThat(result.clusters()).hasSize(1);
        assertThat(result.clusters().getFirst().exceptionType())
                .isEqualTo("NullPointerException");
        assertThat(result.clusters().getFirst().isNew()).isTrue();
    }

    @Test
    @DisplayName("fetchIncidentLogs returns empty list when no errors")
    void shouldReturnEmptyLogsWhenNone() {
        // given
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        given(logSearchProvider.searchLogs(
                        eq(SERVICE_NAME),
                        any(Instant.class),
                        any(Instant.class),
                        eq("ERROR")))
                .willReturn(List.of());

        // when
        var result = agent.fetchIncidentLogs(query);

        // then
        assertThat(result.clusters()).isEmpty();
    }

    @Test
    @DisplayName("fetchIncidentMetrics returns metrics snapshot from Prometheus")
    void shouldFetchIncidentMetrics() {
        // given
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        var expectedMetrics = aMetricsSnapshot();
        given(metricsProvider.fetchServiceMetrics(eq(SERVICE_NAME), any(Instant.class)))
                .willReturn(expectedMetrics);

        // when
        var result = agent.fetchIncidentMetrics(query);

        // then
        assertThat(result.snapshot()).isEqualTo(expectedMetrics);
        assertThat(result.snapshot().service()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchDeployEvents returns deploy snapshots from ArgoCD")
    void shouldFetchDeployEvents() {
        // given
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        var deploys = List.of(aDeploySnapshot());
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(deploys);

        // when
        var result = agent.fetchDeployEvents(query);

        // then
        assertThat(result.deploys()).hasSize(1);
        assertThat(result.deploys().getFirst().appName()).isEqualTo(SERVICE_NAME);
    }

    @Test
    @DisplayName("fetchDeployEvents returns empty list when no deploys")
    void shouldReturnEmptyDeploysWhenNone() {
        // given
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        given(deployHistoryProvider.fetchDeploysInWindow(
                        eq(SERVICE_NAME), any(Instant.class), any(Instant.class)))
                .willReturn(List.of());

        // when
        var result = agent.fetchDeployEvents(query);

        // then
        assertThat(result.deploys()).isEmpty();
    }

    @Test
    @DisplayName("formatPostMortem delegates to PostMortemFormatter and wraps result")
    void shouldFormatPostMortem() {
        // given
        var draft = aPostMortemDraft();
        var query = new PostMortemQuery(INCIDENT_ID, SERVICE_NAME);
        var expectedMarkdown = "# Post-Mortem: SEV2 incident";
        given(postMortemFormatter.format(draft)).willReturn(expectedMarkdown);

        // when
        var result = agent.formatPostMortem(draft, query);

        // then
        assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
        assertThat(result.draft()).isEqualTo(draft);
        assertThat(result.draft().severity()).isEqualTo(IncidentSeverity.SEV2);
        assertThat(result.markdown()).isEqualTo(expectedMarkdown);
        verify(postMortemFormatter).format(draft);
    }

    @Test
    @DisplayName("Blackboard state records are correctly structured")
    void shouldCreateBlackboardRecords() {
        // given
        var entries = List.of(aTimelineEntry());
        var clusters = List.of(aLogCluster());
        var metrics = aMetricsSnapshot();
        var deploys = List.of(aDeploySnapshot());

        // when
        var timeline = new IncidentTimeline(entries);
        var logSnapshot = new IncidentLogSnapshot(clusters);
        var metricsSnapshot = new IncidentMetricsSnapshot(metrics);
        var deploySnapshot = new DeployEventSnapshot(deploys);

        // then
        assertThat(timeline.entries()).isEqualTo(entries);
        assertThat(logSnapshot.clusters()).isEqualTo(clusters);
        assertThat(metricsSnapshot.snapshot()).isEqualTo(metrics);
        assertThat(deploySnapshot.deploys()).isEqualTo(deploys);
    }
}
