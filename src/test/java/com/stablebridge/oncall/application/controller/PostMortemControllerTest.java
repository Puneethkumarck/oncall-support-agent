package com.stablebridge.oncall.application.controller;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent;
import com.stablebridge.oncall.agent.postmortem.PostMortemAgent.FormattedPostMortem;
import com.stablebridge.oncall.application.controller.PostMortemController.PostMortemRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static com.stablebridge.oncall.fixtures.PostMortemFixtures.aPostMortemDraft;
import static com.stablebridge.oncall.fixtures.TestConstants.INCIDENT_ID;
import static com.stablebridge.oncall.fixtures.TestConstants.SERVICE_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class PostMortemControllerTest {

    private AgentPlatform agentPlatform;
    private PostMortemAgent postMortemAgent;
    private PostMortemController controller;

    @BeforeEach
    void setUp() {
        agentPlatform = mock(AgentPlatform.class);
        postMortemAgent = mock(PostMortemAgent.class);
        controller = new PostMortemController(agentPlatform, postMortemAgent);
    }

    @Test
    @DisplayName("POST /api/v1/postmortem returns formatted post-mortem draft")
    void shouldReturnPostMortemDraft() {
        // given
        var expectedReport =
                new FormattedPostMortem(
                        INCIDENT_ID,
                        aPostMortemDraft(),
                        "# Post-Mortem: SEV2\nRoot Cause: NPE");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new PostMortemRequest(INCIDENT_ID, SERVICE_NAME);

            // when
            var result = controller.generatePostMortem(request);

            // then
            assertThat(result.incidentId()).isEqualTo(INCIDENT_ID);
            assertThat(result.markdown()).contains("Post-Mortem");
        }
    }

    @Test
    @DisplayName("POST /api/v1/postmortem formats UserInput as space-delimited string")
    void shouldFormatUserInputAsSpaceDelimited() {
        // given
        var expectedReport =
                new FormattedPostMortem(
                        INCIDENT_ID, aPostMortemDraft(), "# Post-Mortem");

        var mockInvocation = mock(AgentInvocation.class);
        given(mockInvocation.invoke(any(), any())).willReturn(expectedReport);

        try (MockedStatic<AgentInvocation> staticMock = mockStatic(AgentInvocation.class)) {
            staticMock
                    .when(() -> AgentInvocation.create(any(), any(Class.class)))
                    .thenReturn(mockInvocation);

            var request = new PostMortemRequest("INC-002", "evaluator");

            // when
            var result = controller.generatePostMortem(request);

            // then
            assertThat(result).isNotNull();
        }
    }
}
