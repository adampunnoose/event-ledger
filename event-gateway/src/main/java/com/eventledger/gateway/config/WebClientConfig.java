package com.eventledger.gateway.config;

import io.micrometer.observation.ObservationRegistry;
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
 * <p>The {@link ObservationRegistry} is wired in explicitly so client-side observations
 * (and therefore W3C {@code traceparent} propagation to the Account Service) are always
 * active on this client. Resilience4j (circuit breaker, timeout, retry) is layered onto
 * calls made with this client in {@code AccountClient}.
 */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient accountServiceWebClient(
            WebClient.Builder builder,
            ObservationRegistry observationRegistry,
            @Value("${account-service.base-url}") String baseUrl) {
        return builder
                .baseUrl(baseUrl)
                .observationRegistry(observationRegistry)
                .build();
    }
}
