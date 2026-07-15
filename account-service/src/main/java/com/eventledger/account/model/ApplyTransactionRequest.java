package com.eventledger.account.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Request body for {@code POST /accounts/{accountId}/transactions}.
 * {@code accountId} is not here — it is the path variable (single source of truth).
 */
@Data
public class ApplyTransactionRequest {

    @NotBlank(message = "is required")
    private String eventId;

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
}
