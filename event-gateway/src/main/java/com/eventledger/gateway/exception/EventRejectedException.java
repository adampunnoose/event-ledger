package com.eventledger.gateway.exception;

import com.eventledger.gateway.entity.Event;
import lombok.Getter;

/**
 * Raised after the Account Service rejects an event with a client error. Carries the
 * downstream status + body and the stored (REJECTED) event, so the Gateway can return
 * the same status to its caller with a meaningful body.
 */
@Getter
public class EventRejectedException extends RuntimeException {

    private final transient Event event;
    private final int status;
    private final String downstreamBody;

    public EventRejectedException(Event event, int status, String downstreamBody) {
        super("Event " + event.getEventId() + " rejected by the Account Service (status " + status + ")");
        this.event = event;
        this.status = status;
        this.downstreamBody = downstreamBody;
    }
}
