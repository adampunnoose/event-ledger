package com.eventledger.gateway.entity;

/**
 * Lifecycle of an event in the Gateway ledger.
 * RECEIVED → persisted locally; APPLIED → Account Service confirmed the transaction;
 * FAILED → downstream apply failed due to an availability problem (retained for graceful
 * degradation / replay); REJECTED → the Account Service refused the transaction with a
 * client error (e.g. currency mismatch) — a permanent, non-retryable outcome.
 */
public enum EventStatus {
    RECEIVED,
    APPLIED,
    FAILED,
    REJECTED
}
