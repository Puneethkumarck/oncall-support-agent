package com.stablebridge.oncall.infrastructure.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@ConditionalOnClass(HttpClient.class)
class OllamaTimeoutConfig {

    @Bean
    RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(300))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30_000);

        return RestClient.builder()
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));
    }
}
