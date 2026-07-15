package com.eventledger.account.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record AccountDetailsResponse(
        String accountId,
        BigDecimal balance,
        String currency,
        Instant createdAt,
        Instant updatedAt,
        List<TransactionView> recentTransactions) {
}
