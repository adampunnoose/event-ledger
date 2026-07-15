package com.eventledger.gateway.model;

/**
 * Body returned when the Account Service rejects an event with a client error (e.g. a
 * currency mismatch). Carries the (passed-through) downstream error + the stored event
 * (status REJECTED). Returned with the same status the Account Service used.
 */
public record RejectionResponse(
        String error,
        String message,
        EventResponse event,
        String traceId,
        String timestamp) {
}
