package com.eventledger.gateway.entity;

/** Event direction. Defined independently of the Account Service (no shared code). */
public enum TransactionType {
    CREDIT,
    DEBIT
}
