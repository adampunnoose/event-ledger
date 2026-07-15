package com.eventledger.gateway.client;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.reactor.retry.RetryOperator;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * WebClient-based client for the Account Service, wrapped in Resilience4j.
 *
 * <p>The reactive call is composed as <b>retry ( circuitBreaker ( timeout ( call ) ) )</b>:
 * <ul>
 *   <li>{@code timeout} — each attempt is abandoned after {@code account-service.call-timeout}
 *       (prevents the Gateway from hanging on a slow Account Service).</li>
 *   <li>{@code circuitBreaker} — records each attempt; once the failure rate trips the
 *       threshold it opens and fast-fails, so we stop hammering a dead dependency.</li>
 *   <li>{@code retry} — outermost; retries transient failures with exponential backoff +
 *       jitter, but ignores {@code CallNotPermittedException} (no point retrying while open).</li>
 * </ul>
 *
 * <p>Any failure (timeout, connection error, 5xx, or open circuit) propagates out of
 * {@code .block()} and is translated by {@code EventService} into a 503 with the event
 * retained as FAILED (graceful degradation).
 */
@Component
public class AccountClient {

    private final WebClient accountServiceWebClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;
    private final Duration callTimeout;

    public AccountClient(
            WebClient accountServiceWebClient,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry,
            @Value("${account-service.call-timeout}") Duration callTimeout) {
        this.accountServiceWebClient = accountServiceWebClient;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("accountService");
        this.retry = retryRegistry.retry("accountService");
        this.callTimeout = callTimeout;
    }

    public AccountApplyResult applyTransaction(String accountId, AccountApplyRequest request) {
        return accountServiceWebClient.post()
                .uri("/accounts/{accountId}/transactions", accountId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AccountApplyResult.class)
                .timeout(callTimeout)                                   // per-attempt timeout
                .transformDeferred(CircuitBreakerOperator.of(circuitBreaker))
                .transformDeferred(RetryOperator.of(retry))             // outermost
                .block();
    }
}
