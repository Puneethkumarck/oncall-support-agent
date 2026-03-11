package com.stablebridge.oncall.agent.persona;

import com.embabel.agent.prompt.persona.RoleGoalBackstory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OnCallPersonasTest {

    static Stream<Arguments> allPersonas() {
        return Stream.of(
                Arguments.of("SENIOR_SRE", OnCallPersonas.SENIOR_SRE),
                Arguments.of("CLOUD_INFRA_ENGINEER", OnCallPersonas.CLOUD_INFRA_ENGINEER),
                Arguments.of("LOG_ANALYST", OnCallPersonas.LOG_ANALYST),
                Arguments.of("INCIDENT_COMMANDER", OnCallPersonas.INCIDENT_COMMANDER),
                Arguments.of("SRE_MANAGER", OnCallPersonas.SRE_MANAGER),
                Arguments.of(
                        "SENIOR_BLOCKCHAIN_PAYMENTS_ENGINEER",
                        OnCallPersonas.SENIOR_BLOCKCHAIN_PAYMENTS_ENGINEER));
    }

    @ParameterizedTest(name = "{0} has non-blank role, goal, and backstory")
    @MethodSource("allPersonas")
    void personaFieldsAreNonBlank(String name, RoleGoalBackstory persona) {
        assertThat(persona.getRole()).as("%s role", name).isNotBlank();
        assertThat(persona.getGoal()).as("%s goal", name).isNotBlank();
        assertThat(persona.getBackstory()).as("%s backstory", name).isNotBlank();
    }

    @ParameterizedTest(name = "{0} produces a non-blank prompt contribution")
    @MethodSource("allPersonas")
    void personaContributionIsNonBlank(String name, RoleGoalBackstory persona) {
        assertThat(persona.contribution()).as("%s contribution", name).isNotBlank();
    }

    @Test
    @DisplayName("SENIOR_SRE has correct role and focuses on MTTR")
    void seniorSrePersona() {
        assertThat(OnCallPersonas.SENIOR_SRE.getRole())
                .isEqualTo("Senior Site Reliability Engineer");
        assertThat(OnCallPersonas.SENIOR_SRE.getGoal()).contains("root cause");
        assertThat(OnCallPersonas.SENIOR_SRE.getBackstory()).contains("MTTR");
    }

    @Test
    @DisplayName("LOG_ANALYST has correct role and focuses on pattern clustering")
    void logAnalystPersona() {
        assertThat(OnCallPersonas.LOG_ANALYST.getRole())
                .isEqualTo("Production Log Analysis Engineer");
        assertThat(OnCallPersonas.LOG_ANALYST.getGoal()).contains("error patterns");
        assertThat(OnCallPersonas.LOG_ANALYST.getBackstory()).contains("stack trace fingerprint");
    }

    @Test
    @DisplayName("All six personas are defined")
    void allSixPersonasDefined() {
        assertThat(allPersonas().count()).isEqualTo(6);
    }
}
