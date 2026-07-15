package com.eventledger.gateway.exception;

import com.eventledger.gateway.entity.Event;
import lombok.Getter;

/**
 * Thrown when the Gateway cannot apply an event via the Account Service. The event is
 * still persisted locally (status FAILED), so GET endpoints keep working and the stored
 * event is returned in the 503 body. Maps to HTTP 503.
 */
@Getter
public class AccountServiceUnavailableException extends RuntimeException {

    private final transient Event event;

    public AccountServiceUnavailableException(Event event, Throwable cause) {
        super("Account Service unavailable while applying event " + event.getEventId(), cause);
        this.event = event;
    }

    public String getEventId() {
        return event.getEventId();
    }
}
