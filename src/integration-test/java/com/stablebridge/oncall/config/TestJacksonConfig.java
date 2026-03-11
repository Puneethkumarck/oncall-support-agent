package com.stablebridge.oncall.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Provides Jackson2ObjectMapperBuilder required by Embabel's AgentPlatformConfiguration.
 * In a reactive (WebFlux) context, this builder is not auto-configured by Spring Boot.
 */
@TestConfiguration
@SuppressWarnings("removal")
public class TestJacksonConfig {

    @Bean
    Jackson2ObjectMapperBuilder jackson2ObjectMapperBuilder() {
        return new Jackson2ObjectMapperBuilder();
    }
}
