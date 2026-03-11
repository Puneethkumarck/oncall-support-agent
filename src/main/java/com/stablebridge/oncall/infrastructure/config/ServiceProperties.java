package com.stablebridge.oncall.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.services")
public record ServiceProperties(
        ServiceEndpoint loki,
        ServiceEndpoint prometheus,
        ServiceEndpoint tempo,
        GrafanaEndpoint grafana,
        ArgoCDEndpoint argocd,
        PagerDutyEndpoint pagerduty,
        SlackEndpoint slack) {

    public record ServiceEndpoint(String baseUrl) {}

    public record GrafanaEndpoint(String baseUrl, String apiKey) {}

    public record ArgoCDEndpoint(String baseUrl, String authToken) {}

    public record PagerDutyEndpoint(String baseUrl, String apiKey) {}

    public record SlackEndpoint(String webhookUrl) {}
}
