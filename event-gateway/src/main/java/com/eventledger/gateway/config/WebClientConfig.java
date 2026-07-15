package com.eventledger.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient used for the Gateway → Account Service call.
 *
 * <p>Base URL is externalized via {@code account-service.base-url} so it can differ
 * per environment: {@code http://localhost:8081} for a local manual run,
 * {@code http://account-service:8081} under Docker Compose (service-name DNS).
 *
 * <p>Resilience4j (circuit breaker, timeout, retry) is layered onto calls made with
 * this client in Phase 6 — not here.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient accountServiceWebClient(
            WebClient.Builder builder,
            @Value("${account-service.base-url}") String baseUrl) {
        return builder.baseUrl(baseUrl).build();
    }
}
