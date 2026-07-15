package com.eventledger.gateway.model;

/**
 * Body returned on a degraded {@code POST /events} (503): the error envelope plus the
 * stored event (status FAILED), so the client can see exactly what was persisted.
 */
public record DegradedResponse(
        String error,
        String message,
        EventResponse event,
        String traceId,
        String timestamp) {
}
