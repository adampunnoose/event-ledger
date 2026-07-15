package com.eventledger.gateway.client;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient-based client for the Account Service.
 *
 * <p>Phase 5: a plain blocking call ({@code .block()} at the boundary of an MVC request).
 * Phase 6 layers Resilience4j (circuit breaker, timeout, retry) onto this call.
 */
@Component
@RequiredArgsConstructor
public class AccountClient {

    private final WebClient accountServiceWebClient;

    public AccountApplyResult applyTransaction(String accountId, AccountApplyRequest request) {
        return accountServiceWebClient.post()
                .uri("/accounts/{accountId}/transactions", accountId)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(AccountApplyResult.class)
                .block();
    }
}
