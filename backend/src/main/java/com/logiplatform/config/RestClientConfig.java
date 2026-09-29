package com.logiplatform.config;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client configuration. Generic integrations retain conservative timeouts;
 * CargoAi gets a dedicated client because its live Quote & Book search may
 * legitimately wait for the provider's asynchronous aggregation window.
 */
@Configuration
public class RestClientConfig {

    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Bean
    @Qualifier("cargoAiRestTemplate")
    public RestTemplate cargoAiRestTemplate(
            RestTemplateBuilder builder,
            @Value("${aircargo.cargoai.http.connect-timeout-ms:3000}") long connectTimeoutMs,
            @Value("${aircargo.cargoai.http.read-timeout-ms:30000}") long readTimeoutMs) {
        long connect = Math.max(500L, Math.min(connectTimeoutMs, 30_000L));
        long read = Math.max(5_000L, Math.min(readTimeoutMs, 40_000L));
        return builder
                .setConnectTimeout(Duration.ofMillis(connect))
                .setReadTimeout(Duration.ofMillis(read))
                .build();
    }
}
