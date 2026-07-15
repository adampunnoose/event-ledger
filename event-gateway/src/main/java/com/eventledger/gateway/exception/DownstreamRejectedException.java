package com.eventledger.gateway.exception;

import lombok.Getter;

/**
 * Thrown by {@code AccountClient} when the Account Service responds with a 4xx client
 * error (e.g. a currency mismatch). This is a <em>permanent</em> rejection, not an
 * availability failure — so it is configured to be ignored by Resilience4j (no retry,
 * no circuit-breaker failure) and is translated into the same client status upstream.
 */
@Getter
public class DownstreamRejectedException extends RuntimeException {

    private final int status;
    private final String body;

    public DownstreamRejectedException(int status, String body) {
        super("Account Service rejected the transaction with status " + status);
        this.status = status;
        this.body = body;
    }
}
