package com.eventledger.gateway.service;

import com.eventledger.gateway.client.AccountApplyRequest;
import com.eventledger.gateway.entity.Event;
import com.eventledger.gateway.entity.EventStatus;
import com.eventledger.gateway.entity.TransactionType;
import com.eventledger.gateway.model.EventResponse;
import com.eventledger.gateway.model.SubmitEventRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Converts between the API DTOs, the Event entity, and the Account Service payload. */
@Component
@RequiredArgsConstructor
@Slf4j
public class EventMapper {

    private final ObjectMapper objectMapper;

    public Event toEntity(SubmitEventRequest request, EventStatus status, Instant receivedAt) {
        return Event.builder()
                .eventId(request.getEventId())
                .accountId(request.getAccountId())
                .type(TransactionType.valueOf(request.getType()))
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .eventTimestamp(request.getEventTimestamp())
                .metadata(request.getMetadata() == null ? null : request.getMetadata().toString())
                .status(status)
                .receivedAt(receivedAt)
                .build();
    }

    public AccountApplyRequest toApplyRequest(SubmitEventRequest request) {
        return new AccountApplyRequest(
                request.getEventId(),
                request.getType(),
                request.getAmount(),
                request.getCurrency(),
                request.getEventTimestamp());
    }

    public EventResponse toResponse(Event event) {
        JsonNode metadata = null;
        if (event.getMetadata() != null) {
            try {
                metadata = objectMapper.readTree(event.getMetadata());
            } catch (Exception e) {
                log.warn("Could not parse stored metadata for event {}: {}",
                        event.getEventId(), e.getMessage());
            }
        }
        return new EventResponse(
                event.getEventId(),
                event.getAccountId(),
                event.getType().name(),
                event.getAmount(),
                event.getCurrency(),
                event.getEventTimestamp(),
                metadata,
                event.getStatus().name(),
                event.getReceivedAt());
    }
}
