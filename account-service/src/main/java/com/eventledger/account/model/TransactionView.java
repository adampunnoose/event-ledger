package com.eventledger.account.model;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionView(
        String eventId,
        String type,
        BigDecimal amount,
        String currency,
        Instant eventTimestamp) {
}
