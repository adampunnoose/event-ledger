package com.eventledger.gateway.entity;

/**
 * Lifecycle of an event in the Gateway ledger.
 * RECEIVED → persisted locally; APPLIED → Account Service confirmed the transaction;
 * FAILED → downstream apply failed (event is retained for graceful degradation / replay).
 */
public enum EventStatus {
    RECEIVED,
    APPLIED,
    FAILED
}
