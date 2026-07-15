package com.eventledger.gateway.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/** Request body for {@code POST /events}. */
@Data
public class SubmitEventRequest {

    @NotBlank(message = "is required")
    private String eventId;

    @NotBlank(message = "is required")
    private String accountId;

    @NotBlank(message = "is required")
    @Pattern(regexp = "CREDIT|DEBIT", message = "must be CREDIT or DEBIT")
    private String type;

    @NotNull(message = "is required")
    @Positive(message = "must be greater than 0")
    private BigDecimal amount;

    @NotBlank(message = "is required")
    private String currency;

    @NotNull(message = "is required")
    private Instant eventTimestamp;

    /** Optional, opaque additional context. Stored as-is, never interpreted. */
    private JsonNode metadata;
}
