package com.eventledger.gateway.exception;

import lombok.Getter;

/**
 * Thrown when the Gateway cannot apply an event via the Account Service. The event is
 * still persisted locally (status FAILED), so GET endpoints keep working. Maps to HTTP 503.
 */
@Getter
public class AccountServiceUnavailableException extends RuntimeException {

    private final String eventId;

    public AccountServiceUnavailableException(String eventId, Throwable cause) {
        super("Account Service unavailable while applying event " + eventId, cause);
        this.eventId = eventId;
    }
}
