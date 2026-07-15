package com.eventledger.gateway.api;

import com.eventledger.gateway.exception.AccountServiceUnavailableException;
import com.eventledger.gateway.exception.NotFoundException;
import com.eventledger.gateway.model.DegradedResponse;
import com.eventledger.gateway.model.ErrorResponse;
import com.eventledger.gateway.service.EventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private final MeterRegistry meterRegistry;
    private final EventMapper eventMapper;

    public GlobalExceptionHandler(MeterRegistry meterRegistry, EventMapper eventMapper) {
        this.meterRegistry = meterRegistry;
        this.eventMapper = eventMapper;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + " " + fe.getDefaultMessage())
                .orElse("Validation failed");
        meterRegistry.counter("gateway.events.submitted", "result", "rejected").increment();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        meterRegistry.counter("gateway.events.submitted", "result", "rejected").increment();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Malformed or unparseable request body");
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(AccountServiceUnavailableException.class)
    public ResponseEntity<DegradedResponse> handleUnavailable(AccountServiceUnavailableException ex) {
        DegradedResponse body = new DegradedResponse(
                "SERVICE_UNAVAILABLE",
                "Account Service unavailable; event stored with status FAILED. Resubmit to retry.",
                eventMapper.toResponse(ex.getEvent()),
                MDC.get("traceId"),
                Instant.now().toString());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", ex.getMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message) {
        ErrorResponse body = new ErrorResponse(error, message, MDC.get("traceId"), Instant.now().toString());
        return ResponseEntity.status(status).body(body);
    }
}
