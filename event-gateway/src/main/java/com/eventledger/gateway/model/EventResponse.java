package com.eventledger.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;

/** Stored-event body returned by the Gateway (see docs/implementation-plan.md §1.3). */
public record EventResponse(
        String eventId,
        String accountId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp,
        JsonNode metadata,
        String status,
        Instant receivedAt) {
}
