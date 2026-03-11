package com.stablebridge.oncall.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(ServiceProperties.class)
class WebClientConfig {

    @Bean
    WebClient lokiWebClient(ServiceProperties properties) {
        return buildWebClient(properties.loki().baseUrl());
    }

    @Bean
    WebClient prometheusWebClient(ServiceProperties properties) {
        return buildWebClient(properties.prometheus().baseUrl());
    }

    @Bean
    WebClient grafanaWebClient(ServiceProperties properties) {
        return buildWebClient(properties.grafana().baseUrl());
    }

    @Bean
    WebClient tempoWebClient(ServiceProperties properties) {
        return buildWebClient(properties.tempo().baseUrl());
    }

    @Bean
    WebClient pagerdutyWebClient(ServiceProperties properties) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return WebClient.builder()
                .baseUrl(properties.pagerduty().baseUrl())
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader(
                        "Authorization",
                        "Token token=" + properties.pagerduty().apiKey())
                .build();
    }

    @Bean
    WebClient argocdWebClient(ServiceProperties properties) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return WebClient.builder()
                .baseUrl(properties.argocd().baseUrl())
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .defaultHeader(
                        "Authorization",
                        "Bearer " + properties.argocd().authToken())
                .build();
    }

    @Bean
    WebClient slackWebClient(ServiceProperties properties) {
        String url = properties.slack().webhookUrl();
        return buildWebClient(url != null && !url.isBlank() ? url : "http://localhost");
    }

    private WebClient buildWebClient(String baseUrl) {
        var httpClient =
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return WebClient.builder()
                .baseUrl(baseUrl)
                .clientConnector(new JdkClientHttpConnector(httpClient))
                .defaultHeader("Accept", "application/json")
                .build();
    }
}
