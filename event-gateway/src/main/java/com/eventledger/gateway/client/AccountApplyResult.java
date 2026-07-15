package com.eventledger.gateway.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** The Account Service's response to an apply call. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountApplyResult(
        String accountId,
        String eventId,
        boolean applied,
        boolean duplicate,
        BigDecimal balance,
        String currency) {
}
