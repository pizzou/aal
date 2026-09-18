package com.logiplatform.config;

import java.time.Duration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client configuration for outbound carrier/flight-status integrations.
 *
 * <p>
 * Spring Boot 3.3.x does not expose the timeout builder methods used by some
 * newer RestTemplateBuilder examples. Configure the JDK request factory
 * directly
 * so the application remains compatible with the version declared in pom.xml.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(5))
                .build();
    }
}
