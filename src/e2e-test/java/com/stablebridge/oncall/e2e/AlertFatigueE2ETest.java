package com.stablebridge.oncall.e2e;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.domain.io.UserInput;
import com.embabel.agent.test.integration.EmbabelMockitoIntegrationTest;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent;
import com.stablebridge.oncall.agent.fatigue.AlertFatigueAgent.FormattedFatigueReport;
import com.stablebridge.oncall.config.TestJacksonConfig;
import com.stablebridge.oncall.domain.model.fatigue.AlertFatigueReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static com.stablebridge.oncall.fixtures.FatigueFixtures.anAlertFatigueReport;
import static org.assertj.core.api.Assertions.assertThat;

@Import(TestJacksonConfig.class)
@ActiveProfiles("e2e")
class AlertFatigueE2ETest extends EmbabelMockitoIntegrationTest {

    private static final String TARGET_TEAM = "price-alerts";
    private static final int DAYS = 7;

    @Autowired
    private AlertFatigueAgent alertFatigueAgent;

    @Test
    @DisplayName(
            "E2E: Alert fatigue analysis produces report using mock PagerDuty data")
    void shouldProduceAlertFatigueReportFromMockData() {
        // given — stub LLM to return an alert fatigue report
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_TEAM), AlertFatigueReport.class)
                .thenReturn(anAlertFatigueReport());

        // when — run agent with mock PagerDuty adapter (no live PD in E2E stack)
        var input = TARGET_TEAM + " " + DAYS;
        var invocation =
                AgentInvocation.create(agentPlatform, FormattedFatigueReport.class);
        var result = invocation.invoke(new UserInput(input), alertFatigueAgent);

        // then — verify meaningful output
        assertThat(result).isNotNull();
        assertThat(result.team()).isEqualTo(TARGET_TEAM);
        assertThat(result.days()).isEqualTo(DAYS);
        assertThat(result.report()).isNotNull();
        assertThat(result.report().totalAlerts()).isGreaterThanOrEqualTo(0);
        assertThat(result.markdown()).isNotBlank();

        // then — verify LLM was called (meaning data was fetched from mock provider)
        verifyCreateObject(
                prompt -> prompt.contains(TARGET_TEAM) && prompt.contains("fatigue"),
                AlertFatigueReport.class);
        verifyNoMoreInteractions();
    }

    @Test
    @DisplayName(
            "E2E: Alert fatigue agent correctly parses team and days from input")
    void shouldParseTeamAndDaysFromInput() {
        // given — stub LLM
        whenCreateObject(
                        prompt -> prompt.contains(TARGET_TEAM), AlertFatigueReport.class)
                .thenReturn(anAlertFatigueReport());

        // when — run agent with explicit days
        var input = TARGET_TEAM + " 14";
        var invocation =
                AgentInvocation.create(agentPlatform, FormattedFatigueReport.class);
        var result = invocation.invoke(new UserInput(input), alertFatigueAgent);

        // then — verify days were parsed correctly
        assertThat(result).isNotNull();
        assertThat(result.team()).isEqualTo(TARGET_TEAM);
        assertThat(result.days()).isEqualTo(14);
    }
}
