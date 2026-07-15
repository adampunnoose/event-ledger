package com.eventledger.account.model;

import java.math.BigDecimal;

/**
 * Result of applying (or de-duplicating) a transaction.
 *
 * @param applied   whether the transaction's effect is reflected in the balance
 *                  (true for both a fresh apply and a recognized duplicate)
 * @param duplicate whether this call was a no-op because the eventId was already applied
 */
public record ApplyTransactionResponse(
        String accountId,
        String eventId,
        boolean applied,
        boolean duplicate,
        BigDecimal balance,
        String currency) {
}
