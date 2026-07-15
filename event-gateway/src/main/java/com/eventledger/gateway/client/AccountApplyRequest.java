package com.eventledger.gateway.client;

import java.math.BigDecimal;
import java.time.Instant;

/** Payload the Gateway sends to the Account Service. Note: no accountId (it's the path). */
public record AccountApplyRequest(
        String eventId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {
}
