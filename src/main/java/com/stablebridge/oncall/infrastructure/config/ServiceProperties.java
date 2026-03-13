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

    public record ArgoCDEndpoint(String baseUrl, String authToken, java.util.Map<String, String> appMapping) {
        public String resolveAppName(String serviceName) {
            if (appMapping != null && appMapping.containsKey(serviceName)) {
                return appMapping.get(serviceName);
            }
            return serviceName;
        }
    }

    public record PagerDutyEndpoint(String baseUrl, String apiKey) {}

    public record SlackEndpoint(String webhookUrl) {}
}
