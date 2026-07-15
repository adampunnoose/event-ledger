package com.eventledger.account.exception;

/** Thrown when a transaction's currency differs from the account's. Maps to HTTP 409. */
public class CurrencyMismatchException extends RuntimeException {
    public CurrencyMismatchException(String message) {
        super(message);
    }
}
