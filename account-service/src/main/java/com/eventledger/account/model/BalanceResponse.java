package com.eventledger.account.model;

import java.math.BigDecimal;

public record BalanceResponse(String accountId, BigDecimal balance, String currency) {
}
