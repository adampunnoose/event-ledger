package com.eventledger.account.exception;

/** Thrown when an account (or other resource) does not exist. Maps to HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
