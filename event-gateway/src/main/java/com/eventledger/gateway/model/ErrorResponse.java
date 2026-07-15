package com.eventledger.gateway.model;

/** Shared error envelope (see docs/implementation-plan.md §1.1). */
public record ErrorResponse(String error, String message, String traceId, String timestamp) {
}
