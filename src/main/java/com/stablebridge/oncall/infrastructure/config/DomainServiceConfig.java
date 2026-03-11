package com.stablebridge.oncall.infrastructure.config;

import com.stablebridge.oncall.domain.service.DeployImpactReportFormatter;
import com.stablebridge.oncall.domain.service.HealthCardFormatter;
import com.stablebridge.oncall.domain.service.LogAnalysisReportFormatter;
import com.stablebridge.oncall.domain.service.PostMortemFormatter;
import com.stablebridge.oncall.domain.service.SLOReportFormatter;
import com.stablebridge.oncall.domain.service.TimelineBuilder;
import com.stablebridge.oncall.domain.service.TriageReportFormatter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DomainServiceConfig {

    @Bean
    DeployImpactReportFormatter deployImpactReportFormatter() {
        return new DeployImpactReportFormatter();
    }

    @Bean
    HealthCardFormatter healthCardFormatter() {
        return new HealthCardFormatter();
    }

    @Bean
    LogAnalysisReportFormatter logAnalysisReportFormatter() {
        return new LogAnalysisReportFormatter();
    }

    @Bean
    TriageReportFormatter triageReportFormatter() {
        return new TriageReportFormatter();
    }

    @Bean
    PostMortemFormatter postMortemFormatter() {
        return new PostMortemFormatter();
    }

    @Bean
    TimelineBuilder timelineBuilder() {
        return new TimelineBuilder();
    }

    @Bean
    SLOReportFormatter sloReportFormatter() {
        return new SLOReportFormatter();
    }
}
